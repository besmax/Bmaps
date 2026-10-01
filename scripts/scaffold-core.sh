#!/usr/bin/env bash
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
# Required Notice: Copyright (c) 2026 Maksim Bespalov.
# Required Notice: Bmaps — https://github.com/besmax/Bmaps
# License: https://polyformproject.org/licenses/noncommercial/1.0.0
# Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.

set -euo pipefail

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

for module in network database mbtiles datastore storage map-engine di; do
    package_segment="${module//-/_}"
    module_dir="$project_root/core/$module"
    for source_set in commonMain androidMain iosMain; do
        mkdir -p "$module_dir/src/$source_set/kotlin/bes/max/bmaps/core/$package_segment"
    done
    touch "$module_dir/build.gradle.kts"
done
