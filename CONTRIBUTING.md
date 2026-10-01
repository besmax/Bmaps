# Contributing to Bmaps

Read [AGENTS.md](AGENTS.md) and the documentation relevant to your change. Bmaps original code uses [PolyForm Noncommercial 1.0.0](LICENSE); commercial permissions are handled separately as described in [COMMERCIAL-LICENSE.md](COMMERCIAL-LICENSE.md).

Before a nontrivial contribution from another rightsholder is merged, agree in writing with Maksim Bespalov on permission to distribute that contribution under both the public license and separate commercial licenses. A pull request alone is not treated as copyright assignment or blanket permission to relicense. Keep the contributor's applicable copyright notices; do not attribute third-party work to the project owner.

New original source files must retain a short license/provenance header. Run `python3 scripts/license_headers.py` to check headers and packaged notices. The `--write` option adds missing headers to original Bmaps files and synchronizes packaged license documents; review the file ownership first. The checker deliberately rejects an existing foreign license or copyright header instead of replacing it.

Do not insert hidden tracking, intentional defects, or obfuscated watermark strings. Provenance is recorded through explicit notices, version control, and signed release artifacts. See [licensing and provenance](docs/18_LICENSING_AND_PROVENANCE.md).
