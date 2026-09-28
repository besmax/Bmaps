"""Create a sparse 512 MiB raster outside the repository for the host memory check."""
import sys
import tifffile

data = tifffile.memmap(
    sys.argv[1], shape=(16384, 16384), dtype="int16", bigtiff=True,
    rowsperstrip=16, metadata=None, photometric="minisblack",
    extratags=[
        (33550, "d", 3, (0.001, 0.001, 0), False),
        (33922, "d", 6, (0, 0, 0, 10, 50, 0), False),
        (34735, "H", 16, (1, 1, 0, 3, 1024, 0, 1, 2, 1025, 0, 1, 1, 2048, 0, 1, 4326), False),
    ],
)
data[0, 0] = -123
data[8192, 8192] = 456
data[16383, 16383] = 789
data.flush()
