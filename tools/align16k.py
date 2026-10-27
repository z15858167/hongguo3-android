#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 ELF(dynamic lib) 的 PT_LOAD 重排到 16 KB 页对齐。

为什么要有这个脚本：
  上游这份 libmetasec_ml.so 是 4 KB 页对齐（p_align=2**12），Android 15 起有一批
  机型用 16 KB 页，加载这种 so 会直接失败。patchelf 的 --page-size 对已存在的
  LOAD 段不起作用（实测 0.18/0.19 都不动 p_align），所以只能自己搬。

做法：
  不改变任何 vaddr（=> 运行时代码里的绝对地址、以及我们用的「基址 + 偏移」都不受影响），
  只把每个 LOAD 段的**文件偏移**往后挪，使其满足 p_offset % 16384 == p_vaddr % 16384，
  再把 p_align 改成 16384。段之间补 0。

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

    def owner(off, size):
        """这段文件偏移落在哪个 LOAD 里（都不在则返回 None）。"""
        for p in loads:
            if p.offset <= off < p.offset + p.filesz:
                return p
        return None

    plan = []          # [(phdr, old_off, new_off), ...]
    out = bytearray()
    cursor = 0         # 已经拷到新文件的原文件位置
    for p in loads:
        out.extend(blob[cursor:p.offset])
        cursor = p.offset
        want = p.vaddr % PAGE
        x = max(len(out), cursor)
        x += (want - x) % PAGE
        pad = x - len(out)
        if pad:
            out.extend(b'\x00' * pad)
        data = blob[p.offset:p.offset + p.filesz]
        if len(data) != p.filesz:
            sys.exit('文件被截断：%s' % p.offset)
        out.extend(data)
        cursor = p.offset + p.filesz
        plan.append((p, p.offset, x))
    out.extend(blob[cursor:])
    growth = len(out) - len(blob)

    def shifted(off):
        for _p, old, new in plan:
            if old <= off < old + _p.filesz:
                return off + (new - old)
        if off >= plan[-1][1] + plan[-1][0].filesz:
            return off + growth
        return off

    # ---- 回写 program headers ----
    for p in h['phdrs']:
        if p.type == PT_LOAD:
            for lp, old, new in plan:
                if lp is p:
                    p.offset = new
                    p.align = PAGE
                    break
        elif p.offset:
            p.offset = shifted(p.offset)
    h['shoff'] = shifted(h['shoff'])
    for s in h['shdrs']:
        if s.offset:
            s.offset = shifted(s.offset)

    new_phoff = len(out)
    out.extend(b'\x00' * (len(h['phdrs']) * h['phentsize']))
    new_shoff = len(out)
    out.extend(b'\x00' * (len(h['shdrs']) * h['shentsize']))

    for i, p in enumerate(h['phdrs']):
        struct.pack_into('<IIQQQQQQ', out, new_phoff + i * h['phentsize'],
                         p.type, p.flags, p.offset, p.vaddr, p.paddr,
                         p.filesz, p.memsz, p.align)
    for i, s in enumerate(h['shdrs']):
        struct.pack_into('<IIQQQQIIQQ', out, new_shoff + i * h['shentsize'],
                         s.name, s.type, s.flags, s.addr, s.offset, s.size,
                         s.link, s.info, s.addralign, s.entsize)

    struct.pack_into('<Q', out, 0x20, h['phoff'] and new_phoff)
    struct.pack_into('<Q', out, 0x28, new_shoff)
    return bytes(out), plan, growth


def verify(old, new, plan):
    """逐节比对内容，确认搬运没把数据弄坏。"""
    a = parse(old)
    b = parse(new)
    if len(a['phdrs']) != len(b['phdrs']) or len(a['shdrs']) != len(b['shdrs']):
        return '段表/节表数量变了'
    for pa, pb in zip(a['phdrs'], b['phdrs']):
        if (pa.type, pa.vaddr, pa.filesz, pa.memsz) != (pb.type, pb.vaddr, pb.filesz, pb.memsz):
            return 'program header 内容变了: %s' % pa.type
        if pb.type == PT_LOAD:
            if pb.align != PAGE:
                return 'p_align 没改成 16384'
            if pb.offset % PAGE != pb.vaddr % PAGE:
                return 'p_offset 与 p_vaddr 不同余: 0x%x / 0x%x' % (pb.offset, pb.vaddr)
    for sa, sb in zip(a['shdrs'], b['shdrs']):
        if (sa.type, sa.addr, sa.size, sa.name) != (sb.type, sb.addr, sb.size, sb.name):
            return '节表变了: %s' % sa.name
        # SHT_NOBITS(.bss 等) 只占地址不占文件空间，比不了内容
        if sa.type == 8 or not sa.offset or not sa.size:
            continue
        if min(sa.offset, sb.offset) + sa.size > min(len(old), len(new)):
            return '节 %d 越界' % sa.name
        da = old[sa.offset:sa.offset + sa.size]
        db = new[sb.offset:sb.offset + sa.size]
        if da != db:
            return '节 %d 内容和原来不一样' % sa.name
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
    err = verify(blob, new, plan)
    if err:
        sys.exit('校验失败，没有改动任何文件：%s' % err)
    for p, old, cur in plan:
        print('LOAD vaddr=0x%-8x offset 0x%x -> 0x%x' % (p.vaddr, old, cur))
    print('文件增长 %d 字节，所有节内容与原文件一致' % growth)
    open(dst, 'wb').write(new)


if __name__ == '__main__':
    main()
