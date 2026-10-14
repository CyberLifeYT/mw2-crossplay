#!/usr/bin/env python3
"""Where an address lives in an executable: the .pdata function containing it
(if any), and the nearest known starts below and above it (.pdata entries,
branch-and-link targets, starts after padding runs -- the same set
tools/fixbounds.py brackets jump tables with).

For placing explicit function bounds (config/*.toml `functions`) when the
analyser splits wrongly and the C++ build then fails with "use of undeclared
label 'loc_XXXXXXXX'". Addresses only, no game bytes.

    MW2_TITLE=mp MW2_VERSION=tu6 python3 tools/bracket.py 0x8241A84C [...]
"""
import bisect
import struct
import sys

sys.path.insert(0, "tools")
import title

BASE = title.BASE


def starts_of(img):
    TVA, TSZ = title.text(img)

    def w(a):
        return struct.unpack_from(">I", img, a - BASE)[0]

    starts = set(title.functions(img))
    prev_pad = False
    for a in range(TVA, TVA + TSZ, 4):
        x = w(a)
        if (x >> 26) == 18 and (x & 1):                       # bl
            d = x & 0x03FFFFFC
            if d & 0x02000000:
                d -= 0x04000000
            t = d if (x & 2) else a + d
            if TVA <= t < TVA + TSZ:
                starts.add(t)
        if prev_pad and x != 0:
            starts.add(a)
        prev_pad = (x == 0)
    return sorted(starts), title.functions(img)


def main():
    img = title.image()
    starts, fns = starts_of(img)
    for arg in sys.argv[1:]:
        a = int(arg, 16)
        i = bisect.bisect_right(starts, a)
        below = starts[i - 1] if i > 0 else None
        above = starts[i] if i < len(starts) else None
        cont = [(s, fns[s]) for s in fns if s <= a < s + fns[s]]
        print(f"0x{a:08X}: start-below "
              f"{f'0x{below:08X}' if below is not None else 'none'}, start-above "
              f"{f'0x{above:08X}' if above is not None else 'none'}, pdata: " +
              (", ".join(f"0x{s:08X}+0x{z:X}" for s, z in cont) or "none"))


if __name__ == "__main__":
    main()
