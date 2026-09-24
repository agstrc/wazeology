#!/usr/bin/env python3
"""Add (or replace) one entry in a zip from a local file, preserving every other entry.

Usage: zip_add.py <apk> <entry_name> <local_file>

Used to drop the compiled classes.dex into the aapt2-linked installer apk.
"""
import os
import sys
import zipfile


def main():
    if len(sys.argv) != 4:
        print("usage: zip_add.py <apk> <entry_name> <local_file>", file=sys.stderr)
        return 2
    apk, name, local = sys.argv[1:4]
    with open(local, "rb") as f:
        data = f.read()
    tmp = apk + ".tmp"
    with zipfile.ZipFile(apk) as zin:
        with zipfile.ZipFile(tmp, "w") as zout:
            for item in zin.infolist():
                if item.filename == name:
                    continue
                zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
                zi.compress_type = item.compress_type
                zout.writestr(zi, zin.read(item.filename))
            zout.writestr(name, data, compress_type=zipfile.ZIP_STORED)
    os.replace(tmp, apk)
    print("added {} ({} bytes)".format(name, len(data)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
