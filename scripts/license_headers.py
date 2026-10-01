# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
# Required Notice: Copyright (c) 2026 Maksim Bespalov.
# Required Notice: Bmaps — https://github.com/besmax/Bmaps
# License: https://polyformproject.org/licenses/noncommercial/1.0.0
# Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.

import argparse
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
BLOCK_SUFFIXES = {".kt", ".kts", ".java", ".swift", ".c", ".h"}
HASH_SUFFIXES = {".py", ".sh", ".cmake", ".def"}
EXCLUDED_PARTS = {"build", ".cxx", ".gradle", ".git", "nativeSources", "vendor", "third_party"}
LINES = (
    "SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0",
    "Required Notice: Copyright (c) 2026 Maksim Bespalov.",
    "Required Notice: Bmaps — https://github.com/besmax/Bmaps",
    "License: https://polyformproject.org/licenses/noncommercial/1.0.0",
    "Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.",
)
PACKAGED = ROOT / "feature/shell/src/commonMain/composeResources/files"
DOCUMENTS = {
    "LICENSE": "bmaps_license.txt",
    "NOTICE": "bmaps_notice.txt",
    "COMMERCIAL-LICENSE.md": "bmaps_commercial_license.txt",
}


def source_paths():
    output = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT
    )
    for name in sorted(set(output.decode().split("\0")) - {""}):
        path = Path(name)
        if EXCLUDED_PARTS.intersection(path.parts):
            continue
        if path.suffix in BLOCK_SUFFIXES | HASH_SUFFIXES or path.name == "CMakeLists.txt":
            if (ROOT / path).is_file() and not (ROOT / path).is_symlink():
                yield path


def main():
    parser = argparse.ArgumentParser(description="Check Bmaps source notices and packaged licensing documents.")
    parser.add_argument("--write", action="store_true", help="Add missing original-code headers and sync packaged documents.")
    args = parser.parse_args()
    failures = []
    count = 0
    for relative in source_paths():
        count += 1
        path = ROOT / relative
        source = path.read_text(encoding="utf-8")
        prefix = ""
        if source.startswith("#!"):
            prefix, _, source = source.partition("\n")
            prefix += "\n"
        if relative.suffix in BLOCK_SUFFIXES:
            header = "/*\n" + "\n".join(LINES) + "\n*/\n\n"
        else:
            header = "\n".join("# " + line for line in LINES) + "\n\n"
        if source.startswith(header):
            continue
        if not args.write:
            failures.append(f"Missing or changed Bmaps header: {relative}")
        elif re.search(r"copyright|SPDX-License-Identifier", "\n".join(source.splitlines()[:20]), re.I):
            failures.append(f"Review existing ownership/license before editing: {relative}")
        else:
            path.write_text(prefix + header + source, encoding="utf-8")

    for original, packaged in DOCUMENTS.items():
        expected = (ROOT / original).read_bytes()
        destination = PACKAGED / packaged
        if destination.exists() and destination.read_bytes() == expected:
            continue
        if args.write:
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(expected)
        else:
            failures.append(f"Packaged document differs from {original}: {destination.relative_to(ROOT)}")
    for failure in failures:
        print(failure, file=sys.stderr)
    print(f"Checked {count} source headers and {len(DOCUMENTS)} packaged documents; {len(failures)} issue(s).")
    return bool(failures)


if __name__ == "__main__":
    sys.exit(main())
