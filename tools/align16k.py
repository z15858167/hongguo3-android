#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 ELF(dynamic lib) 的 PT_LOAD 重排到 16 KB 页对齐。

为什么要有这个脚本：
  上游这份 libmetasec_ml.so 是 4 KB 页对齐（p_align=2**12），Android 15 起有一批
  机型用 16 KB 页，加载这种 so 会直接失败。patchelf 的 --page-size 对已存在的
  LOAD 段不起作用（实测 0.18/0.19 都不动 p_align），所以只能自己搬。

做法（v2，修复了 v1 的致命回归）：
  不改变任何 vaddr（=> 运行时代码里的绝对地址、以及我们用的「基址 + 偏移」都不受影响），
  只把需要挪的 LOAD 段的**文件偏移**向后挪，使其满足 p_offset % 16384 == p_vaddr % 16384，
  再把 p_align 改成 16384。

  v1 的致命 bug：把 program header 表也搬到了文件末尾。Android linker 要求
  phdr 表必须落在某个 PT_LOAD 的文件范围内（否则报
  "loaded phdr ... not in loadable segment"，so 加载即失败）。
  v2 不再搬 phdr 表：它本来就在第一个 LOAD（偏移 0，无需挪）里，原地不动。

用法: python3 tools/align16k.py libmetasec_ml.so [-o out.so]   不带 -o 则原地改
"""

import struct
import sys

PAGE = 16384
PT_LOAD = 1
EHDR_SIZE = 64
PHDR_SIZE = 56
SHDR_SIZE = 64


class Phdr(object):
    __slots__ = ('type', 'flags', 'offset', 'vaddr', 'paddr', 'filesz', 'memsz', 'align')


class Shdr(object):
    __slots__ = ('name', 'type', 'flags', 'addr', 'offset', 'size', 'link', 'info',
                 'addralign', 'entsize')


def parse(blob):
    assert blob[:4] == b'\x7fELF' and blob[4] == 2, '只处理 ELF64'
    e_phoff, e_shoff = struct.unpack_from('<QQ', blob, 0x20)
    e_phentsize, e_phnum, e_shentsize, e_shnum = struct.unpack_from('<HHHH', blob, 0x36)
    phdrs = []
    for i in range(e_phnum):
        p = Phdr()
        (p.type, p.flags, p.offset, p.vaddr, p.paddr,
         p.filesz, p.memsz, p.align) = struct.unpack_from('<IIQQQQQQ', blob,
                                                          e_phoff + i * e_phentsize)
        phdrs.append(p)
    shdrs = []
    for i in range(e_shnum):
        s = Shdr()
        (s.name, s.type, s.flags, s.addr, s.offset, s.size, s.link, s.info,
         s.addralign, s.entsize) = struct.unpack_from('<IIQQQQIIQQ', blob,
                                                      e_shoff + i * e_shentsize)
        shdrs.append(s)
    return {
        'phoff': e_phoff, 'shoff': e_shoff,
        'phentsize': e_phentsize, 'phnum': e_phnum,
        'shentsize': e_shentsize, 'shnum': e_shnum,
        'phdrs': phdrs, 'shdrs': shdrs,
    }


def reAlign(blob):
    h = parse(blob)
    loads = sorted([p for p in h['phdrs'] if p.type == PT_LOAD], key=lambda p: p.offset)
    if not loads:
        sys.exit('没有 PT_LOAD')

    # ---- 计划：每个 LOAD 的新文件偏移（只增不减，且不与前面的段重叠）----
    plan = []                       # [(phdr, old_off, new_off), ...]
    prev_end = 0                    # 前一个 LOAD 在新文件里的结束位置
    for p in loads:
        want = p.vaddr % PAGE
        new = p.offset
        if new % PAGE != want:
            new = p.offset + ((want - p.offset) % PAGE)
            if new < prev_end:
                new += ((prev_end - new + PAGE - 1) // PAGE) * PAGE
        plan.append((p, p.offset, new))
        prev_end = max(prev_end, new + p.filesz)

    # phdr 表必须仍落在某个 LOAD 的文件范围内（linker 硬性要求）
    phoff, phsz = h['phoff'], h['phnum'] * h['phentsize']
    if not any(new <= phoff and phoff + phsz <= new + p.filesz for p, _old, new in plan):
        sys.exit('重排后 phdr 表(0x%x)不在任何 LOAD 段内，拒绝执行' % phoff)

    def delta(off):
        """文件位置 off 处的内容，因 LOAD 挪动而产生的位移。"""
        d = 0
        for _p, old, new in plan:
            if old <= off:
                d += new - old
        return d

    # ---- 重建文件：段间插 0，段内容原样拷贝 ----
    out = bytearray()
    prev = 0
    for p, old, new in plan:
        out.extend(blob[prev:old])              # 两个 LOAD 之间的原有内容
        out.extend(b'\x00' * (new - old))       # 补齐到新偏移
        out.extend(blob[old:old + p.filesz])    # LOAD 内容原样
        prev = old + p.filesz
    out.extend(blob[prev:])                     # 段后的内容（节表等），整体右移

    # ---- 回写 program headers（表本身的位置不动！）----
    for p in h['phdrs']:
        if p.type == PT_LOAD:
            for lp, old, new in plan:
                if lp is p:
                    p.offset = new
                    p.align = PAGE
                    break
        elif p.offset:
            p.offset += delta(p.offset)

    h['shoff'] += delta(h['shoff'])
    for s in h['shdrs']:
        if s.offset:
            s.offset += delta(s.offset)

    # e_phoff 不改：phdr 表原地没动。e_shoff 改成新位置。
    struct.pack_into('<Q', out, 0x28, h['shoff'])

    # 把 phdr 表内容写回原位置（值变了：LOAD 的 offset/align、非 LOAD 的 offset）
    for i, p in enumerate(h['phdrs']):
        struct.pack_into('<IIQQQQQQ', out, h['phoff'] + i * h['phentsize'],
                         p.type, p.flags, p.offset, p.vaddr, p.paddr,
                         p.filesz, p.memsz, p.align)
    for i, s in enumerate(h['shdrs']):
        struct.pack_into('<IIQQQQIIQQ', out, h['shoff'] + i * h['shentsize'],
                         s.name, s.type, s.flags, s.addr, s.offset, s.size,
                         s.link, s.info, s.addralign, s.entsize)

    growth = len(out) - len(blob)
    return bytes(out), plan, growth


def verify(old, new):
    """逐项确认重排没有破坏任何东西，并满足 bionic linker 的硬性要求。"""
    a = parse(old)
    b = parse(new)

    # 1) phdr 表位置不变，且在新文件里落在某个 LOAD 内
    if a['phoff'] != b['phoff']:
        return 'phdr 表位置被移动了（bionic 会拒绝加载）'
    phend = b['phoff'] + b['phnum'] * b['phentsize']
    inside = any(p.type == PT_LOAD and p.offset <= b['phoff'] and phend <= p.offset + p.filesz
                 for p in b['phdrs'])
    if not inside:
        return 'phdr 表不在任何 LOAD 段内（bionic: loaded phdr not in loadable segment）'

    # 2) 每个 LOAD：offset 与 vaddr 同余（mod 16384 也 mod 4096），vaddr 不变
    for pa, pb in zip(a['phdrs'], b['phdrs']):
        if (pa.type, pa.vaddr, pa.filesz, pa.memsz) != (pb.type, pb.vaddr, pb.filesz, pb.memsz):
            return 'program header 内容变了: type=%s' % pa.type
        if pb.type == PT_LOAD:
            if pb.align != PAGE:
                return 'p_align 没改成 16384'
            if pb.offset % PAGE != pb.vaddr % PAGE:
                return 'p_offset 与 p_vaddr 不同余: 0x%x / 0x%x' % (pb.offset, pb.vaddr)

    # 3) LOAD 在文件里按序且不重叠
    ls = sorted([p for p in b['phdrs'] if p.type == PT_LOAD], key=lambda p: p.offset)
    for x, y in zip(ls, ls[1:]):
        if x.offset + x.filesz > y.offset:
            return 'LOAD 段文件区间重叠'

    # 4) 节内容逐一比对（NOBITS 与空节跳过）
    for sa, sb in zip(a['shdrs'], b['shdrs']):
        if (sa.type, sa.addr, sa.size, sa.name) != (sb.type, sb.addr, sb.size, sb.name):
            return '节表变了: %s' % sa.name
        if sa.type == 8 or not sa.offset or not sa.size:
            continue
        if sb.offset + sa.size > len(new):
            return '节 %d 越界' % sa.name
        if old[sa.offset:sa.offset + sa.size] != new[sb.offset:sb.offset + sa.size]:
            return '节 %d 内容和原来不一样' % sa.name

    # 5) DYNAMIC 段的文件偏移必须落在某个 LOAD 内（运行时 linker 会经 vaddr 访问，其实
    #    只依赖 vaddr，但保守起见检查偏移一致性）
    for p in b['phdrs']:
        if p.type == 2:  # PT_DYNAMIC
            if not any(ld.type == PT_LOAD and ld.offset <= p.offset < ld.offset + ld.filesz
                       for ld in b['phdrs'] if ld.type == PT_LOAD):
                return 'PT_DYNAMIC 偏移不在 LOAD 内'
    return None


def main():
    args = [a for a in sys.argv[1:]]
    if not args:
        sys.exit(__doc__)
    src = args[0]
    dst = src
    if '-o' in args:
        dst = args[args.index('-o') + 1]
    blob = open(src, 'rb').read()
    new, plan, growth = reAlign(blob)
    err = verify(blob, new)
    if err:
        sys.exit('校验失败，没有改动任何文件：%s' % err)
    for p, old, cur in plan:
        print('LOAD vaddr=0x%-8x offset 0x%x -> 0x%x' % (p.vaddr, old, cur))
    print('文件增长 %d 字节，校验全部通过（phdr 原位、节内容一致、同余满足）' % growth)
    open(dst, 'wb').write(new)


if __name__ == '__main__':
    main()
