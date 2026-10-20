/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.mapbuilder.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PackageFailureSerializationTest {
    @Test
    fun persistedFailuresKeepTheirOriginalDiscriminatorsAndFields() {
        val failures = listOf(
            "NotFound" to PackageFailure.NotFound,
            "NotReady" to PackageFailure.NotReady,
            "CorruptData" to PackageFailure.CorruptData,
            "UnsupportedVersion" to PackageFailure.UnsupportedVersion(7),
            "UnsupportedContent" to PackageFailure.UnsupportedContent,
            "UnsupportedCoordinateSystem" to PackageFailure.UnsupportedCoordinateSystem,
            "NetworkUnavailable" to PackageFailure.NetworkUnavailable,
            "AuthenticationRequired" to PackageFailure.AuthenticationRequired,
            "ElevationCredentialsRequired" to PackageFailure.ElevationCredentialsRequired,
            "ElevationAccessDenied" to PackageFailure.ElevationAccessDenied,
            "ElevationUnavailable" to PackageFailure.ElevationUnavailable,
            "RateLimited" to PackageFailure.RateLimited(1000),
            "ProviderDownloadNotAllowed" to PackageFailure.ProviderDownloadNotAllowed,
            "TileUnavailable" to PackageFailure.TileUnavailable,
            "SizeLimitExceeded" to PackageFailure.SizeLimitExceeded(100, 200),
            "InsufficientStorage" to PackageFailure.InsufficientStorage(null),
            "Io" to PackageFailure.Io,
            "Conflict" to PackageFailure.Conflict,
            "ActivityInProgress" to PackageFailure.ActivityInProgress,
        )
        for ((name, failure) in failures) {
            val fields = when (failure) {
                is PackageFailure.UnsupportedVersion -> ",\"version\":7"
                is PackageFailure.RateLimited -> ",\"retryAfterMillis\":1000"
                is PackageFailure.SizeLimitExceeded -> ",\"limitBytes\":100,\"requiredBytes\":200"
                is PackageFailure.InsufficientStorage -> ",\"requiredBytes\":null"
                else -> ""
            }
            val persisted = """{"type":"bes.max.bmaps.domain.mapbuilder.PackageFailure.$name"$fields}"""
            assertEquals(failure, Json.decodeFromString<PackageFailure>(persisted), name)
            assertEquals(Json.parseToJsonElement(persisted),
                Json.parseToJsonElement(Json.encodeToString<PackageFailure>(failure)), name)
        }
    }
}
