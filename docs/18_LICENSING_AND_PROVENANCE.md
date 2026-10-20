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

Android packaging uses the catalog-pinned NDK through `app.android.application`, matching `app.proj.android` and `app.tiff.android`. The selected NDK must be installed so AGP can use its strip tool during final APK packaging. Keep native debug information outside the distributed APK; stripping does not remove the embedded PROJ database or change the supported ABIs.

After changing the packaging NDK, the owner must rebuild the signed release APK and inspect the build output for `Unable to strip` warnings. Inspect the packaged `lib/*/*.so` files for `.debug_*` sections, rather than relying on the strip task's successful completion. The existing 280,519,788-byte APK contained unstripped native libraries; applying NDK `llvm-strip --strip-unneeded` to temporary copies reduced their combined size from 239,107,592 to 63,600,588 bytes. This suggests an approximately 105 MB universal APK before any R8 changes; it is an estimate, not verification of a rebuilt artifact. The packaging configuration change still requires a new owner-built release and runtime acceptance.

The same convention plugin enables R8 code optimization and resource shrinking only for release, using `proguard-android-optimize.txt` and `androidApp/proguard-rules.pro`, following [Android's optimization guidance](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization). JNI entry points retain their native modules' consumer rules. The pinned WorkManager dependency supplies worker-name/constructor rules, Room supplies database rules, and Kotlin Serialization supplies serializer rules. Application rules retain source and line information for stack-trace recovery. Archive `androidApp/build/outputs/mapping/release/mapping.txt` with each release's exact APK; mappings from another build cannot reliably decode its stack traces.

Release optimization still requires an owner-built signed APK and device acceptance. Verify startup/navigation and packaged fonts/icons/notices; opening existing maps and activity data; package/GPX/GeoJSON import/export; coordinate conversion and DEM sampling; location/recording; and download/elevation work restoration, including upgrading over a previous installation with pending work. Focused documentation checks do not exercise R8 or establish release runtime correctness. Measure the rebuilt APK to establish the additional size reduction.

Signing requires the owner's existing GPG or SSH signing key and its corresponding Git/GitHub setup. This change creates no keys, changes no signing configuration, and signs no commits or releases. Once the owner has configured signing, use signed commits (`git commit -S`) and annotated signed release tags (`git tag -s <version> -m <message>`). Verify signatures with `git verify-commit <commit>` and `git verify-tag <version>`.

For each release, retain the tagged source archive, application artifacts, full commit ID, and SHA-256 checksums of those artifacts. Sign the checksum manifest using the owner's configured signing tool and publish the verification information alongside the release. A checksum alone proves neither authorship nor publication date. Tagging a release does not by itself sign APK/IPA files or a checksum manifest. Do not publish a release until its contents and licensing notices have been reviewed.

## Verification and remaining work

Header and packaged-document checks are independent of app tests. Android/iOS builds and visual acceptance remain with the user. Release signing setup and a complete third-party dependency notice inventory remain release tasks. This policy does not assert that signatures or release artifacts already exist.
