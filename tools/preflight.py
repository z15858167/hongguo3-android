#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""推送前的本地静态体检。ghpush.py 每次推送会先跑这个，不通过就不推。

检查项（都是能在本地、不需要真机就确定的硬性要求）：
  1. jniLibs 里每个 .so：
     - 是 ELF64 / AArch64 动态库
     - phdr 表必须落在某个 PT_LOAD 的文件范围内
       （bionic: "loaded phdr not in loadable segment" —— v0.1.1 的翻车点）
     - 每个 PT_LOAD：p_offset ≡ p_vaddr (mod 页大小)，p_align=16384 时按 16384 查，
       否则按 4096 查（4KB 机型是绝大多数，这条不过连正常手机都加载不了）
     - PT_LOAD 文件区间有序且不重叠
     - PT_DYNAMIC 的偏移落在某个 PT_LOAD 内
  2. assets/config.json 是合法 JSON 且有 api_host / base_query
  3. AndroidManifest.xml / strings.xml / themes.xml 是合法 XML
  4. 关键的 JNI 宿主类存在（so 的 FindClass/RegisterNatives 依赖）：
     ms/bd/c/m.java、ms/bd/c/a4.java、com/bytedance/mobsec/metasec/ml/MS.java
  5. so 依赖的 NEEDED 库都在 jniLibs 里，或属于系统库白名单
"""

import json
import os
import struct
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JNILIB = os.path.join(ROOT, 'app/src/main/jniLibs/arm64-v8a')

SYSTEM_LIBS = {
    'liblog.so', 'libandroid.so', 'libm.so', 'libdl.so', 'libc.so',
    'libc++_shared.so', 'libz.so', 'libEGL.so', 'libGLESv2.so',
    'libOpenSLES.so', 'libstdc++.so', 'libdl_android.so',
}

PT_LOAD, PT_DYNAMIC = 1, 2
EM_AARCH64 = 0xB7


def err(msg):
    print('✗ ' + msg)
    return 1


def ok(msg):
    print('✓ ' + msg)
    return 0


def parse_elf(blob):
    if blob[:4] != b'\x7fELF' or blob[4] != 2:
        return None
    (e_type, e_machine) = struct.unpack_from('<HH', blob, 0x10)
    e_phoff, e_shoff = struct.unpack_from('<QQ', blob, 0x20)
    e_phentsize, e_phnum = struct.unpack_from('<HH', blob, 0x36)
    phdrs = []
    for i in range(e_phnum):
        fields = struct.unpack_from('<IIQQQQQQ', blob, e_phoff + i * e_phentsize)
        phdrs.append(dict(zip(
            ('type', 'flags', 'offset', 'vaddr', 'paddr', 'filesz', 'memsz', 'align'),
            fields)))
    return {'type': e_type, 'machine': e_machine, 'phoff': e_phoff,
            'phnum': e_phnum, 'phentsize': e_phentsize, 'phdrs': phdrs}


def check_so(path):
    rc = 0
    name = os.path.basename(path)
    blob = open(path, 'rb').read()
    e = parse_elf(blob)
    if e is None:
        return err('%s: 不是 ELF64' % name)
    if e['machine'] != EM_AARCH64:
        rc |= err('%s: 不是 AArch64 (machine=%d)' % (name, e['machine']))
    if e['type'] != 3:
        rc |= err('%s: 不是动态库 (e_type=%d)' % (name, e['type']))

    loads = [p for p in e['phdrs'] if p['type'] == PT_LOAD]
    loads.sort(key=lambda p: p['offset'])

    # phdr 表在某个 LOAD 内 —— bionic 硬性要求
    ph0, ph1 = e['phoff'], e['phoff'] + e['phnum'] * e['phentsize']
    if not any(p['offset'] <= ph0 and ph1 <= p['offset'] + p['filesz'] for p in loads):
        rc |= err('%s: phdr 表(0x%x..0x%x)不在任何 LOAD 段内，bionic 会拒绝加载'
                  % (name, ph0, ph1))

    # 同一个要求从"运行时地址"侧再验一遍：bionic 的 FindPhdr 是在按 vaddr 映射好
    # LOAD 之后去找 phdr 的，所以 phdr 的 vaddr 也必须落在某个 LOAD 的 vaddr 区间内
    ph_vaddr = None
    for p in loads:
        if p['offset'] <= e['phoff'] < p['offset'] + p['filesz']:
            ph_vaddr = p['vaddr'] + (e['phoff'] - p['offset'])
            break
    if ph_vaddr is None or not any(
            p['vaddr'] <= ph_vaddr and ph_vaddr + e['phnum'] * e['phentsize']
            <= p['vaddr'] + p['memsz'] for p in loads):
        rc |= err('%s: 按 vaddr 换算后 phdr 表不在任何 LOAD 内（FindPhdr 会失败）' % name)

    for i, p in enumerate(loads):
        page = p['align'] if p['align'] >= 4096 else 4096
        if p['offset'] % page != p['vaddr'] % page:
            rc |= err('%s: LOAD%d p_offset=0x%x 与 p_vaddr=0x%x 不同余 (mod %d)'
                      % (name, i, p['offset'], p['vaddr'], page))
        if p['offset'] + p['filesz'] > len(blob):
            rc |= err('%s: LOAD%d 文件区间越界' % (name, i))

    for a, b in zip(loads, loads[1:]):
        if a['offset'] + a['filesz'] > b['offset']:
            rc |= err('%s: LOAD 段文件区间重叠' % name)

    for p in e['phdrs']:
        if p['type'] == PT_DYNAMIC:
            if not any(ld['offset'] <= p['offset'] < ld['offset'] + ld['filesz']
                       for ld in loads):
                rc |= err('%s: PT_DYNAMIC 偏移(0x%x)不在 LOAD 内' % (name, p['offset']))

    # NEEDED 依赖
    needed = []
    dyn = next((p for p in e['phdrs'] if p['type'] == PT_DYNAMIC), None)
    if dyn:
        # 简单扫 .dynamic：从 phdr 指向的文件区域逐项读 (tag, val)
        off, end = dyn['offset'], dyn['offset'] + dyn['filesz']
        strtab = strsz = None
        tags = []
        for pos in range(off, end - 16, 16):
            tag, val = struct.unpack_from('<QQ', blob, pos)
            if tag == 0:
                break
            tags.append((tag, val))
        for tag, val in tags:
            if tag == 5:   # DT_STRTAB
                strtab = val
            elif tag == 10:  # DT_STRSZ
                strsz = val
        if strtab is not None and strsz:
            for tag, val in tags:
                if tag == 1:  # DT_NEEDED
                    s = blob[strtab + val: strtab + strsz].split(b'\0')[0]
                    needed.append(s.decode('utf-8', 'replace'))
    local = set(os.listdir(JNILIB))
    for n in needed:
        if n not in local and n not in SYSTEM_LIBS:
            rc |= err('%s: NEEDED %s 既不在 jniLibs 也不是系统库' % (name, n))

    if rc == 0:
        ok('%s: ELF 结构全部通过（NEEDED: %s）' % (name, ', '.join(needed) or '无'))
    return rc


def main():
    rc = 0

    # 1) so 静态检查
    for f in sorted(os.listdir(JNILIB)):
        if f.endswith('.so'):
            rc |= check_so(os.path.join(JNILIB, f))

    # 2) config.json
    try:
        cfg = json.load(open(os.path.join(ROOT, 'app/src/main/assets/config.json'),
                             encoding='utf-8'))
        assert cfg.get('api_host') and len(cfg.get('base_query', {})) >= 1
        ok('config.json: 合法且含 api_host/base_query')
    except Exception as e:
        rc |= err('config.json: %s' % e)

    # 3) XML 合法性
    for rel in ('app/src/main/AndroidManifest.xml',
                'app/src/main/res/values/strings.xml',
                'app/src/main/res/values/themes.xml'):
        try:
            ET.parse(os.path.join(ROOT, rel))
            ok(rel + ': 合法 XML')
        except Exception as e:
            rc |= err(rel + ': %s' % e)

    # 4) JNI 宿主类存在
    for rel in ('app/src/main/java/ms/bd/c/m.java',
                'app/src/main/java/ms/bd/c/a4.java',
                'app/src/main/java/com/bytedance/mobsec/metasec/ml/MS.java'):
        if os.path.isfile(os.path.join(ROOT, rel)):
            ok(rel.split('/')[-1] + ': 在')
        else:
            rc |= err('缺少 JNI 宿主类: %s' % rel)

    # 5) 本地已编译出 APK 时，把包里实际打包的 so 也过一遍同样的检查
    #    （AGP 会做 strip 等处理，仓库里的 so 合格不代表打包后的合格）
    apk = os.path.join(ROOT, 'app/build/outputs/apk/release/app-release.apk')
    if os.path.isfile(apk):
        import tempfile
        import zipfile
        import shutil
        tmp = tempfile.mkdtemp(prefix='preflight_apk_')
        try:
            with zipfile.ZipFile(apk) as z:
                libs = [n for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')]
                for n in libs:
                    z.extract(n, tmp)
            bad = False
            for n in sorted(libs):
                if check_so(os.path.join(tmp, n)) != 0:
                    bad = True
            if bad:
                rc |= err('APK 内 so 体检未通过: %s' % apk)
            else:
                ok('APK 内 %d 个 so 全部通过: %s' % (len(libs), os.path.basename(apk)))
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    if rc:
        sys.exit('体检未通过，取消推送')
    print('体检全部通过')


if __name__ == '__main__':
    main()
