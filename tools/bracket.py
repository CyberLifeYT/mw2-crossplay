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


def flow_map(img, start, end):
    """Control-flow structure of an address range: branches, calls, returns
    and padding, as addresses only. Enough to place an explicit function
    bound around a construct the analyser split wrongly."""

    def w(a):
        return struct.unpack_from(">I", img, a - BASE)[0]

    def target(addr, v, bits):
        if v & (1 << (bits - 1)):
            v -= 1 << bits
        return v

    a = start
    while a < end:
        x = w(a)
        if x == 0:
            b = a
            while b < end and w(b) == 0:
                b += 4
            print(f"  0x{a:08X}..0x{b:04X} pad")
            a = b
            continue
        op = x >> 26
        aa, lk = x & 2, x & 1
        if op == 18:                                  # b, bl
            li = x & 0x03FFFFFC
            t = target(a, li, 26) if aa else a + target(a, li, 26)
            print(f"  0x{a:08X} {'bl' if lk else 'b'} -> 0x{t:08X}")
        elif op == 16:                                # bc
            bo, bi = (x >> 21) & 31, (x >> 16) & 31
            bd = x & 0xFFFC
            t = target(a, bd, 16) if aa else a + target(a, bd, 16)
            ctr = "ctr?" if ((bo >> 2) & 1) == 0 else "-"
            print(f"  0x{a:08X} bc(BO={bo},BI={bi},{ctr}) -> 0x{t:08X}{' +link' if lk else ''}")
        elif op == 19:                                # bclr/bcctr/...
            xo = (x >> 1) & 0x3FF
            what = {16: "bclr", 528: "bcctr"}.get(xo, f"op19/{xo}")
            print(f"  0x{a:08X} {what}{'+link' if lk else ''}")
        else:
            print(f"  0x{a:08X} ...")
        a += 4


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
    args = sys.argv[1:]
    flow = None
    addrs = []
    while args:
        a = args.pop(0)
        if a == "--flow":
            start = int(args.pop(0), 16)
            end = int(args.pop(0), 16)
            flow = (start, end)
        else:
            addrs.append(a)
    img = title.image()
    if flow is not None:
        flow_map(img, *flow)
        return
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
