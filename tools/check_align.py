#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 ELF 的 PT_LOAD 段是不是 16 KB 页对齐（tools/align16k.py 之后用）。

判断标准按 linker 的真实要求，而不是 Google 那个更严的脚本：
    p_align >= 16384  且  p_offset ≡ p_vaddr (mod 16384)
第二条才是关键 —— bionic 用 PAGE_START(p_offset) 去 mmap，只有 p_offset 落在页内的偏移
和 p_vaddr 落在页内的偏移一致，才能拿到正确的内容。
现在这批 so 就好端端跑在 4 KB 页设备上，而它第二段的 p_offset=0x32b328 根本不是 4096 的
倍数，这就证明 p_offset 本身不需要整除页大小，只要和 p_vaddr 同余。

用法: python3 tools/check_align.py libmetasec_ml.so     通过返回 0，否则返回 1
"""
import struct
import sys

PT_LOAD = 1


def main():
    path = sys.argv[1]
    blob = open(path, 'rb').read()
    if blob[:4] != b'\x7fELF' or blob[4] != 2:
        print('只支持 ELF64')
        return 1
    e_phoff, = struct.unpack_from('<Q', blob, 0x20)
    e_phentsize, e_phnum = struct.unpack_from('<HH', blob, 0x36)
    ok = True
    loads = 0
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        if struct.unpack_from('<I', blob, off)[0] != PT_LOAD:
            continue
        loads += 1
        # Elf64_Phdr: type(4) flags(4) offset vaddr paddr filesz memsz align
        p_offset, p_vaddr, _p, _fsz, _msz, p_align = \
            struct.unpack_from('<QQQQQQ', blob, off + 8)
        print('  LOAD p_offset=0x%x p_vaddr=0x%x p_align=0x%x' % (p_offset, p_vaddr, p_align))
        if p_align % 16384 != 0:
            ok = False
            print('    x p_align 不是 16384 的倍数')
        if (p_offset % 16384) != (p_vaddr % 16384):
            ok = False
            print('    x p_offset 与 p_vaddr 不同余')
    print('PT_LOAD %d 段，16KB 对齐: %s' % (loads, '通过' if ok else '失败'))
    return 0 if ok and loads > 0 else 1


if __name__ == '__main__':
    sys.exit(main())
