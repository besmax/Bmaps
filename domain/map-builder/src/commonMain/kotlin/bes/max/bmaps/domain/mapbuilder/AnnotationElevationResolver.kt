/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.domain.mapbuilder

import bes.max.bmaps.core.di.AppScope
import bes.max.bmaps.core.mapengine.GeographicCoordinate
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.*

fun interface AnnotationElevationResolver {
    suspend fun resolve(id: PackageId, values: List<Annotation>): List<Annotation>
}

@Inject
@ContributesBinding(AppScope::class)
class PackageAnnotationElevationResolver(private val packages: PackageRepository) : AnnotationElevationResolver {
    override suspend fun resolve(id: PackageId, values: List<Annotation>): List<Annotation> {
        if (values.all { value -> value.coordinates.indices.all { value.elevations.getOrNull(it) != null } }) return values
        var session: OpenedPackage? = null
        val samples = linkedMapOf<GeographicCoordinate, AnnotationElevation?>()
        try {
            val opened = (packages.open(id) as? PackageResult.Success)?.value ?: return values
            session = opened
            if (opened.manifest.elevation == null) return values
            return values.map { value ->
                val elevations = value.coordinates.mapIndexed { index, point ->
                    currentCoroutineContext().ensureActive()
                    value.elevations.getOrNull(index) ?: if (samples.containsKey(point)) samples[point] else {
                        val elevation = try { opened.elevation(point.latitude, point.longitude) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            ElevationDiagnostics.error("annotation_elevation_sample", error)
                            PackageElevation.Unavailable
                        }
                        (elevation as? PackageElevation.Value)?.takeIf { it.meters.isFinite() }?.let {
                            AnnotationElevation(it.meters, it.verticalReference)
                        }.also {
                            samples[point] = it
                            while (samples.size > 2048) samples.remove(samples.keys.first())
                        }
                    }
                }
                value.copy(elevations = elevations.takeIf { it.any { sample -> sample != null } }.orEmpty())
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            ElevationDiagnostics.error("annotation_elevation_open", error)
            return values
        } finally {
            withContext(NonCancellable) {
                try { session?.close() }
                catch (error: Exception) { ElevationDiagnostics.error("annotation_elevation_close", error) }
            }
        }
    }
}
