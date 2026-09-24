#!/usr/bin/env python3
"""Print "<dex count> <next dex name>" for an apk (classes.dex counts as index 1).

The payload dex must land at the next contiguous index, so build.sh uses this to derive the
expected classesN.dex name from the pristine base and to assert it against Pins.java.
"""
import re
import sys
import zipfile


def main():
    if len(sys.argv) != 2:
        print("usage: dex_info.py <apk>", file=sys.stderr)
        return 2
    with zipfile.ZipFile(sys.argv[1]) as z:
        idx = []
        for n in z.namelist():
            if n == "classes.dex":
                idx.append(1)
            m = re.match(r"^classes(\d+)\.dex$", n)
            if m:
                idx.append(int(m.group(1)))
    idx.sort()
    print(len(idx), "classes{}.dex".format(idx[-1] + 1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
