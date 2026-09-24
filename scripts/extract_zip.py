#!/usr/bin/env python3
"""Extract named entries from a zip into a destination directory, optionally renaming them.

Usage: extract_zip.py <src.zip> <dest_dir> NAME[=OUTNAME]... [--match SUBSTR=OUTNAME]...

Runs inside the toolchain container (cwd = repo root). A script file, not an inline heredoc:
run_tools has no `docker run -i`, so `python3 - <<PY` would read empty stdin and silently no-op.
"""
import argparse
import os
import sys
import zipfile


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dest")
    ap.add_argument("names", nargs="*", help="NAME or NAME=OUTNAME (exact entry names)")
    ap.add_argument("--match", action="append", default=[], metavar="SUBSTR=OUTNAME",
                    help="first entry whose name contains SUBSTR, written as OUTNAME")
    a = ap.parse_args()

    os.makedirs(a.dest, exist_ok=True)
    with zipfile.ZipFile(a.src) as z:
        names = set(z.namelist())
        for spec in a.names:
            name, _, out = spec.partition("=")
            out = out or name
            if name not in names:
                print("FATAL: entry not found in {}: {}".format(a.src, name), file=sys.stderr)
                return 1
            with open(os.path.join(a.dest, out), "wb") as f:
                f.write(z.read(name))
        for spec in a.match:
            sub, _, out = spec.partition("=")
            hit = None
            for n in z.namelist():
                if sub in n:
                    hit = n
                    break
            if hit is None:
                print("FATAL: no entry matching '{}' in {}".format(sub, a.src), file=sys.stderr)
                return 1
            with open(os.path.join(a.dest, out), "wb") as f:
                f.write(z.read(hit))
    return 0


if __name__ == "__main__":
    sys.exit(main())
