# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
# Required Notice: Copyright (c) 2026 Maksim Bespalov.
# Required Notice: Bmaps — https://github.com/besmax/Bmaps
# License: https://polyformproject.org/licenses/noncommercial/1.0.0
# Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.

"""Regenerate synthetic fixtures using an independent TIFF writer, not the reader under test."""
from pathlib import Path
import hashlib
import json
import numpy as np
import tifffile

ROOT = Path(__file__).parent / "fixtures"
ROOT.mkdir(exist_ok=True)


def tags(point=False, crs=4326):
    return [
        (33550, "d", 3, (0.25, 0.25, 0.0), False),
        (33922, "d", 6, (0, 0, 0, 10, 50, 0), False),
        (34735, "H", 20, (1, 1, 0, 4, 1024, 0, 1, 2, 1025, 0, 1, 2 if point else 1,
                          2048, 0, 1, crs, 2054, 0, 1, 9102), False),
    ]


def write(name, data, extra=None, **options):
    tifffile.imwrite(ROOT / name, data, metadata=None, photometric="minisblack",
                     extratags=tags() if extra is None else extra, **options)


data = np.arange(35, dtype=np.int16).reshape(5, 7) - 10
data[2, 3] = -32768
for byteorder in ("<", ">"):
    for compression in (None, "lzw", "deflate", "packbits"):
        name = f"int16-{'le' if byteorder == '<' else 'be'}-{compression or 'none'}.tif"
        write(name, data, tags() + [(42113, "s", 0, "-32768", False)],
              rowsperstrip=2, byteorder=byteorder, compression=compression,
              predictor=2 if compression in ("lzw", "deflate") else None)

tile = (np.arange(17 * 19, dtype=np.float32).reshape(17, 19) - 100) / 4
tile[3, 4] = np.nan
write("float32-tiled-bigtiff.tif", tile, tags(True) + [(42113, "s", 0, "nan", False)],
      tile=(16, 16), bigtiff=True, compression="deflate", predictor=3)
write("float32-numeric-nodata.tif", np.array([[0.1, 0.0]], dtype=np.float32),
      tags() + [(42113, "s", 0, "0.1", False)])
write("float64-be.tif", np.array([[-0.125, 1.5, np.inf]], dtype=np.float64), byteorder=">")
write("uint32.tif", np.array([[0, 4294967295]], dtype=np.uint32))
write("unsupported-crs.tif", data, tags(crs=4269))
write("missing-georeference.tif", data, [])
write("unsupported-orientation.tif", data, tags() + [(274, "H", 1, 4, False)])
write("unsupported-int64.tif", data.astype(np.int64))
write("oversized-strip.tif", np.zeros((1, 4194305), dtype=np.int16),
      [(33550, "d", 3, (0.0000001, 0.25, 0), False)] + tags()[1:], compression="deflate")
write("unsupported-transform.tif", data, tags() + [
    (34264, "d", 16, (1, 0, 0, 10, 0, -1, 0, 50, 0, 0, 0, 0, 0, 0, 0, 1), False)])
write("unsupported-multiband.tif", np.zeros((5, 7, 2), dtype=np.int16), planarconfig="contig")
truncated = (ROOT / "int16-le-none.tif").read_bytes()[:-1]
(ROOT / "truncated-strip.tif").write_bytes(truncated)
(ROOT / "invalid.tif").write_bytes(b"not a TIFF")

manifest = {}
for path in sorted(ROOT.glob("*.tif")):
    manifest[path.name] = {"sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    if path.name.startswith("gdal-"):
        with tifffile.TiffFile(path) as source:
            values = source.asarray()
            manifest[path.name]["reference_samples"] = [
                {"row": row, "column": column, "value": int(values[row, column])}
                for row, column in ((0, 0), (10, 10), (100, 100), (119, 119), (120, 120))
            ]
(ROOT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
