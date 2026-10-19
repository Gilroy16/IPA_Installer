#!/usr/bin/env python3
"""Rewrites an APK so that stored (uncompressed) entries are 4-byte aligned (resources.arsc must be stored + aligned)."""
import sys, zipfile

STORE = {"resources.arsc"}

def main(src, dst):
    with zipfile.ZipFile(src) as zin, open(dst, "wb") as raw:
        with zipfile.ZipFile(raw, "w") as zout:
            for info in zin.infolist():
                data = zin.read(info.filename)
                ni = zipfile.ZipInfo(info.filename, date_time=(2008, 1, 1, 0, 0, 0))
                ni.external_attr = info.external_attr
                stored = info.filename in STORE or info.filename.endswith(".png")
                ni.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
                if stored:
                    # local header = 30 bytes + name + extra; pad extra so data starts on a 4-byte boundary
                    offset = raw.tell() + 30 + len(ni.filename.encode())
                    pad = (4 - offset % 4) % 4
                    ni.extra = b"\x00" * pad
                zout.writestr(ni, data)

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
