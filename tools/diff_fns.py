#!/usr/bin/env python3
"""Which functions differ between the disc (TU0) and title-update-6 images.

Compares .text bytes per .pdata function and prints only what changed:
address, TU0/TU6 sizes and short hashes. No game bytes, only layout facts --
for placing explicit analyser bounds around a construct the update moved.

    python3 tools/diff_fns.py mp [0x8241A84C]
"""
import hashlib
import struct
import sys

BASE = 0x82000000


def read(pe):
    return open(pe, "rb").read()


def sections(img):
    pe = struct.unpack_from("<I", img, 0x3C)[0]
    count = struct.unpack_from("<H", img, pe + 6)[0]
    optional = struct.unpack_from("<H", img, pe + 20)[0]
    out = {}
    for i in range(count):
        o = pe + 24 + optional + i * 40
        name = img[o:o + 8].rstrip(b"\0").decode()
        size, rva = struct.unpack_from("<II", img, o + 8)
        out[name] = (BASE + rva, size)
    return out


def functions(img):
    secs = sections(img)
    va, size = secs[".pdata"]
    pd = img[va - BASE:va - BASE + size]
    out = {}
    for i in range(len(pd) // 8):
        a, d = struct.unpack_from(">II", pd, i * 8)
        if a == 0:
            break
        out[a] = ((d >> 8) & 0x3FFFFF) * 4
    return out


def digest(img, addr, size):
    h = hashlib.sha256()
    h.update(img[addr - BASE:addr - BASE + size])
    return h.hexdigest()[:16]


def main():
    title = sys.argv[1] if len(sys.argv) > 1 else "mp"
    focus = int(sys.argv[2], 16) if len(sys.argv) > 2 else None
    pe = "default_mp.pe" if title == "mp" else "default.pe"
    a = read(f"mw2/tu0/{pe}")
    b = read(f"mw2/{pe}")
    fa, fb = functions(a), functions(b)
    print(f"tu0 functions: {len(fa)}, tu6 functions: {len(fb)}")
    only_b = sorted(set(fb) - set(fa))
    only_a = sorted(set(fa) - set(fb))
    print(f"added: {len(only_b)}, removed: {len(only_a)}")
    for x in only_b[:30]:
        print(f"  + 0x{x:08X} size 0x{fb[x]:X}")
    for x in only_a[:30]:
        print(f"  - 0x{x:08X} size 0x{fa[x]:X}")
    n = 0
    for x in sorted(set(fa) & set(fb)):
        if fa[x] != fb[x] or digest(a, x, fa[x]) != digest(b, x, fb[x]):
            if n < 60:
                print(f"  ~ 0x{x:08X} tu0 +0x{fa[x]:X} {digest(a, x, fa[x])} "
                      f"tu6 +0x{fb[x]:X} {digest(b, x, fb[x])}")
            n += 1
    print(f"changed: {n}")
    if focus is not None:
        print(f"--- within 0x1000 of 0x{focus:08X} (tu6) ---")
        for x in sorted(fb):
            if abs(x - focus) <= 0x1000 or (x <= focus < x + fb[x]):
                mark = " <= CONTAINS FOCUS" if x <= focus < x + fb[x] else ""
                same = "same" if x in fa and fa[x] == fb[x] and digest(a, x, fa[x]) == digest(b, x, fb[x]) else "DIFFERS"
                print(f"    0x{x:08X} +0x{fb[x]:X} [{same}]{mark}")


if __name__ == "__main__":
    main()
