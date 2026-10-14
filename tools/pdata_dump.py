#!/usr/bin/env python3
"""Every .pdata function (start address and size) in an executable.
Addresses only, no game data -- for diffing two versions' layouts when the
analyser splits one differently than the other.

    MW2_TITLE=mp MW2_VERSION=tu6 python3 tools/pdata_dump.py
"""
import sys

sys.path.insert(0, "tools")
import title

img = title.image()
for a in sorted(fns := title.functions(img)):
    print(f"{a:08X} {fns[a]:X}")
