#!/usr/bin/env python3
"""Jump-table blocks recorded near an address, for placing explicit function
bounds when the analyser split wrongly. Prints each [[switch]] block whose
addresses come within 0x400 of the address: its range, how many addresses it
holds, and its first lines. Addresses only, no game data.

    python3 tools/tables_near.py config/mw2mp_switch_tables.toml 0x8241A84C
"""
import re
import sys

text = open(sys.argv[1]).read()
label = int(sys.argv[2], 16)
for blk in text.split("[[switch]]")[1:]:
    addrs = [int(x, 16) for x in re.findall(r"0x([0-9A-Fa-f]+)", blk)]
    if not addrs:
        continue
    if min(addrs) - 0x400 <= label <= max(addrs) + 0x400:
        head = blk.strip().splitlines()[:4]
        print(f"  ... range 0x{min(addrs):08X}..0x{max(addrs):08X}, {len(addrs)} addresses")
        for ln in head:
            print("   ", ln[:160])
