#!/usr/bin/env python3
"""Map the runtime's hooked functions from the disc (TU0) executables to the
title-update-6 ones, for OUR copies of both.

runtime/title.h names guest addresses per executable and version. The TU0
blocks are verified (those builds run); the TU6 blocks were derived for
another copy and go stale as \"use of undeclared identifier 'sub_XXXXXXXX'\"
at build time. This finds each TU6 address the same way the TU0 ones were
found (title.h header comment): same instructions except what the linker
filled in, masked out -- branch displacements and the halves of absolute
addresses.

    MW2_VERSION=tu6 python3 tools/map_tu6.py [--words N]

Reads mw2/tu0/default{,_mp}.pe (made with tools/xexdump.py when missing) and
mw2/default{,_mp}.pe, and prints, per title, the title.h lines the TU6 blocks
should hold. Only unambiguous same-size matches are trusted; the rest are
flagged for reading. A unique match of another size, or several matches, is
printed with its sizes so a human can decide.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))

import title  # noqa: F401  (BASE only; images are named below, not from env)
from find_in_title import Image

BASE = title.BASE
WORDS = 24


def block_addrs(text):
    """{name: address} for the bare-hex function entries of one #if block."""
    return {n: int(a, 16)
            for n, a in re.findall(r"#define (T_\w+)\s+([0-9A-F]{8})\b", text)}


def load(pe):
    if not os.path.exists(pe):
        xex = pe.replace(".pe", ".xex").replace("mw2/default", "mw2/tu0/default")
        print(f"  making {pe} from the disc executable...")
        r = os.system(f"python3 tools/xexdump.py {xex} {pe} >/dev/null 2>&1")
        if r != 0 or not os.path.exists(pe):
            print(f"  !! cannot make {pe}")
            return None
    return Image(os.path.join(ROOT, pe))


def main():
    args = sys.argv[1:]
    words = WORDS
    while args:
        a = args.pop(0)
        if a == "--words":
            words = int(args.pop(0))

    text = open(os.path.join(ROOT, "runtime/title.h")).read()
    blocks = re.split(r"^#(?:if|elif|else|endif)\b.*$", text, flags=re.M)[1:5]
    # title.h order: the update's multiplayer and campaign, then the disc's.
    tu6mp, tu6sp, tu0mp, tu0sp = (block_addrs(b) for b in blocks)

    images = {}
    for key, pe in (("tu0sp", "mw2/tu0/default.pe"),
                    ("tu6sp", "mw2/default.pe"),
                    ("tu0mp", "mw2/tu0/default_mp.pe"),
                    ("tu6mp", "mw2/default_mp.pe")):
        images[key] = load(pe)

    for title_name, tu6, tu0, tu0img, tu6img in (
            ("campaign", tu6sp, tu0sp, images["tu0sp"], images["tu6sp"]),
            ("multiplayer", tu6mp, tu0mp, images["tu0mp"], images["tu6mp"])):
        print(f"===== {title_name} =====")
        if tu0img is None or tu6img is None:
            print("  missing image, skipping")
            continue
        for name in tu6:
            if name not in tu0:
                print(f"#define {name:24} {tu6[name]:08X}   !! no TU0 counterpart, kept blind")
                continue
            src, want = tu0[name], tu6[name]
            size = tu0img.sizes.get(src)
            tried = []
            hits = []
            for n in (words, 40):
                tried.append(n)
                sig = tu0img.signature(src, n if size is None else max(4, min(n, size // 4)))
                hits = tu6img.find(sig)
                if len(hits) == 1:
                    break
            tag = ""
            if len(hits) == 1:
                got = tu6img.sizes.get(hits[0])
                if size is not None and got not in (None, size):
                    tag = f"   !! size differs (tu0 {size}, tu6 {got})"
                if hits[0] == want:
                    tag += "   (unchanged)"
            elif not hits:
                tag = "   !! NO MATCH"
            else:
                shown = ", ".join(f"{h:08X} ({tu6img.sizes.get(h, '-')} bytes)" for h in hits[:6])
                tag = f"   !! ambiguous ({len(hits)}): {shown}"
            addr = f"{hits[0]:08X}" if len(hits) == 1 else f"{want:08X}"
            print(f"#define {name:24} {addr}  ({src:08X} on disc){tag}")


if __name__ == "__main__":
    main()
