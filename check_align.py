#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 ELF 的 PT_LOAD 段是不是 16 KB 页对齐（CI 里 patchelf 之后用）。

用法: python3 check_align.py libmetasec_ml.so     通过返回 0，否则返回 1
"""
import struct
import sys


def main():
    path = sys.argv[1]
    blob = open(path, 'rb').read()
    if blob[:4] != b'\x7fELF':
        print('不是 ELF')
        return 1
    e_phoff, = struct.unpack_from('<Q', blob, 0x20)
    e_phentsize, e_phnum = struct.unpack_from('<HH', blob, 0x36)
    ok = True
    loads = 0
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        if struct.unpack_from('<I', blob, off)[0] != 1:      # PT_LOAD
            continue
        loads += 1
        # Elf64_Phdr: type(4) flags(4) offset vaddr paddr filesz memsz align
        p_offset, _v, _p, _fsz, _msz, p_align = \
            struct.unpack_from('<QQQQQQ', blob, off + 8)
        if p_align != 16384 or p_offset % 16384 != 0:
            ok = False
            print('  段 %d: p_align=0x%x p_offset=0x%x' % (i, p_align, p_offset))
    print('PT_LOAD %d 段，16KB 对齐: %s' % (loads, '通过' if ok else '失败'))
    return 0 if ok and loads > 0 else 1


if __name__ == '__main__':
    sys.exit(main())
