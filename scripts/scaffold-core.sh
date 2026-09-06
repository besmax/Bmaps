#!/usr/bin/env bash
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
