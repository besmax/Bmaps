# Third-party materials

The root PolyForm Noncommercial license applies to original Bmaps material unless a file or directory identifies different terms. It does not replace upstream licenses or claim ownership of third-party material. A separate Bmaps commercial agreement cannot waive upstream obligations.

Existing third-party notices include:

| Material | Notice or provenance |
| --- | --- |
| Inter fonts | `core/ui/src/commonMain/composeResources/files/inter_license.txt` |
| libtiff and its LZW implementation | `core/ui/src/commonMain/composeResources/files/libtiff_license.txt`; upstream source archives retain their notices |
| GDAL reference DEM | `core/tiff-native/src/test/fixtures/GDAL-LICENSE.txt` and the adjacent `README.md` |
| Gradle wrapper scripts and distribution | Existing wrapper headers and the upstream Gradle distribution |
| PROJ, its database, and SQLite | Upstream notices in the catalog-pinned source archives; the Bmaps C/JNI wrappers are separate original code |
| Okio SHA-256 primitive | Catalog-pinned Okio artifact, Apache-2.0; upstream artifact notices retained. Transfer file IO uses kotlinx-io. |
| XMLUtil core | [Upstream XMLUtil](https://github.com/pdvrieze/xmlutil), Apache-2.0; catalog-pinned artifact and upstream notices. Used for common GPX XML parsing. |
| Kotlin, Compose, MapComposeMP, and other dependencies | Their upstream licenses and artifact notices; versions are in `gradle/libs.versions.toml` |

This is an ownership boundary and a navigation aid, not an exhaustive release dependency-license inventory. Review and bundle all required upstream notices for the actual artifacts shipped before release. Map/provider attribution remains separately required and is displayed by the map features.

Do not add Bmaps ownership headers to vendored sources, externally sourced assets, or generated dependencies. Preserve their original notices. Record the origin and applicable license of any newly imported material before merging it.
