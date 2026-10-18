# Contributing to Bmaps

Read [AGENTS.md](AGENTS.md) and the documentation relevant to your change. Bmaps original code uses [PolyForm Noncommercial 1.0.0](LICENSE); commercial permissions are handled separately as described in [COMMERCIAL-LICENSE.md](COMMERCIAL-LICENSE.md).

Before a nontrivial contribution from another rightsholder is merged, agree in writing with Maksim Bespalov on permission to distribute that contribution under both the public license and separate commercial licenses. A pull request alone is not treated as copyright assignment or blanket permission to relicense. Keep the contributor's applicable copyright notices; do not attribute third-party work to the project owner.

New original source files must retain a short license/provenance header. Run `python3 scripts/license_headers.py` to check headers and packaged notices. The `--write` option adds missing headers to original Bmaps files and synchronizes packaged license documents; review the file ownership first. The checker deliberately rejects an existing foreign license or copyright header instead of replacing it.

Do not insert hidden tracking, intentional defects, or obfuscated watermark strings. Provenance is recorded through explicit notices, version control, and signed release artifacts. See [licensing and provenance](docs/18_LICENSING_AND_PROVENANCE.md).

## Verification responsibilities

Follow the authoritative [verification policy](AGENTS.md#verification-policy): the user owns project builds and all device/emulator/simulator runs; agents run focused tests without devices, then ask before running the full suite. Compilation needed by a selected test task is allowed. Report actual results separately from authored checks and pending native acceptance. Historical handoffs do not change these responsibilities.

## Documentation maintenance

Use the [task guide](AGENTS.md#project-documentation) to find relevant sections. Update current contracts in place, keep application status in document 5 and activity status in document 22, and link to detailed contracts instead of copying update paragraphs. The large documents have section outlines; no split is required yet. Every Markdown document under `docs/`, including proposals, must have an entry in the AGENTS task guide.

Run the focused documentation checks from the repository root:

```sh
python3 -B -m unittest discover -s scripts -p 'test_check_docs.py'
python3 -B scripts/check_docs.py
python3 -B scripts/license_headers.py
git diff --check
```

`check_docs.py` validates local Markdown links and heading/HTML anchors in root Markdown files and `docs/`, task-guide coverage of `docs/`, and module-registry agreement with literal `include(":module")` declarations in `settings.gradle.kts`. Module entries use standalone ``* **`:module`**`` bullets in document 3; every included project must have its conventional `build.gradle.kts` path. The Xcode-only iOS host and composite `build-logic` build are not Gradle project entries.

The checker supports inline and explicit reference links, ATX/Setext headings, repeated GitHub-style heading anchors and local relative paths, including URL-encoded paths. It skips fenced examples, inline code, HTML comments and external URLs. It does not fetch external links, resolve prose references such as "document 22", evaluate dynamic Gradle settings or verify the semantic correctness of contracts. Keep task-guide entries as actual Markdown links with section anchors where useful. If Gradle project paths become dynamic or use custom directories, update the checker and its focused regression tests with that change.

The [Documentation workflow](.github/workflows/documentation.yml) runs the checker and its focused tests on pushes and pull requests, without building or launching the app. The separate [License notices workflow](.github/workflows/license-notices.yml) checks source headers and packaged notices.
