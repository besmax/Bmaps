# Licensing and provenance

Adopted 2026-09-29 at the project owner's request. Rightsholder: Maksim Bespalov. Canonical project: https://github.com/besmax/Bmaps. Licensing contact: bespalov.m.9@gmail.com.

## Public and commercial permissions

`LICENSE` contains the unmodified official PolyForm Noncommercial 1.0.0 text. `NOTICE` identifies the rightsholder and canonical URL using the license's `Required Notice:` mechanism. `COMMERCIAL-LICENSE.md` describes how to request individually negotiated additional permissions; it is not itself a commercial license. Permissions already granted by the public license remain available without individual approval, including its organizational exceptions. This is source-available software with noncommercial restrictions, not an OSI open-source license.

The policy applies to original Bmaps material. Upstream licenses and ownership remain intact; see `THIRD-PARTY-NOTICES.md`. Previously granted rights are not retroactively revoked by adding these files. External contributions require an explicit rights agreement before they can be offered under separate commercial terms; see `CONTRIBUTING.md`.

## Provenance notices

Original Kotlin/Kotlin DSL, Java, Swift, C headers/sources, Python, shell, CMake, and cinterop definition files carry SPDX identification, copyright, canonical project URL, and a license URL. File headers also travel with standalone components copied as files. When extracting a fragment, carry the applicable notice and license URL with it. There is no hidden runtime tracking or deliberately altered algorithm. These notices establish traceable origin; they cannot prevent someone from deleting a comment or override statutory exceptions.

`scripts/license_headers.py` checks tracked and untracked non-ignored original sources and synchronizes the root license documents with the copies packaged by `feature:shell`. Generated build outputs, downloaded native sources, Gradle wrapper scripts, binary fixtures, and externally licensed assets are outside the header writer's scope. GitHub Actions runs the check on pushes and pull requests. Review ownership before using `--write`; the script is not a copyright audit.

Settings includes a selectable About Bmaps/licensing card, including the installed app version code, the owner, canonical URL, public-license URL, and commercial contact, available offline. Full license, notice, and commercial request policy texts are bundled as Compose resources on both platforms. This section identifies Bmaps licensing; it does not claim to be a complete third-party license browser.

## Signed release procedure

Signing requires the owner's existing GPG or SSH signing key and its corresponding Git/GitHub setup. This change creates no keys, changes no signing configuration, and signs no commits or releases. Once the owner has configured signing, use signed commits (`git commit -S`) and annotated signed release tags (`git tag -s <version> -m <message>`). Verify signatures with `git verify-commit <commit>` and `git verify-tag <version>`.

For each release, retain the tagged source archive, application artifacts, full commit ID, and SHA-256 checksums of those artifacts. Sign the checksum manifest using the owner's configured signing tool and publish the verification information alongside the release. A checksum alone proves neither authorship nor publication date. Tagging a release does not by itself sign APK/IPA files or a checksum manifest. Do not publish a release until its contents and licensing notices have been reviewed.

## Verification and remaining work

Header and packaged-document checks are independent of app tests. Android/iOS builds and visual acceptance remain with the user. Release signing setup and a complete third-party dependency notice inventory remain release tasks. This policy does not assert that signatures or release artifacts already exist.
