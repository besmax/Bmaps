/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.storage

import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.*
import kotlinx.io.files.*

data class PackageStorageLocation(val path: String)

class StorageLimitExceeded(val limit: Long, val required: Long) : IOException("Package size limit exceeded")
class StorageCapacityExceeded(val required: Long) : IOException("Insufficient storage")
class UnsafePackagePath : IOException("Unsafe package path")
class PackageAlreadyExists : IOException("Package already exists")

@Inject
@SingleIn(AppScope::class)
class PackageFileStorage(private val location: PackageStorageLocation) {
    private val mutex = Mutex()

    suspend fun <T> access(block: suspend PackageFiles.() -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            SystemFileSystem.createDirectories(Path(location.path))
            PackageFiles(SystemFileSystem.resolve(Path(location.path))).block()
        }
    }
}

class PackageFiles internal constructor(private val root: Path) {
    private val fs = SystemFileSystem

    fun elevationJobDirectory(id: String): Path {
        checkComponent(id)
        return checked(Path(root, "elevation-jobs", id))
    }

    fun elevationJobIds(): List<String> {
        val parent = checked(Path(root, "elevation-jobs"))
        if (!fs.exists(parent)) return emptyList()
        return fs.list(parent).filter { fs.metadataOrNull(checked(it))?.isDirectory == true }
            .map { it.name }.onEach(::checkComponent)
    }

    fun readElevationJob(id: String): String? {
        val path = checked(Path(elevationJobDirectory(id), "job.json"))
        if (!fs.exists(path)) return null
        val length = checkNotNull(fs.metadataOrNull(path)).size
        require(length in 1L..65_536L)
        return fs.source(path).buffered().use { it.readByteArray(length.toInt()).decodeToString() }
    }

    fun writeElevationJob(id: String, json: String) {
        val directory = elevationJobDirectory(id)
        fs.createDirectories(directory)
        val path = checked(Path(directory, "job.json"))
        val temporary = checked(Path(directory, "job.json.part"))
        val bytes = json.encodeToByteArray()
        require(bytes.size <= 65_536)
        requireCapacity(bytes.size.toLong() + 65_536)
        fs.sink(temporary).buffered().use { it.write(bytes) }
        syncPath(temporary.toString(), false)
        fs.atomicMove(temporary, path)
        syncPath(directory.toString(), true)
    }

    fun readElevationRange(id: String): String? {
        val path = checked(Path(elevationJobDirectory(id), "range.json"))
        if (!fs.exists(path)) return null
        val length = checkNotNull(fs.metadataOrNull(path)).size
        require(length in 1L..65_536L)
        return fs.source(path).buffered().use { it.readByteArray(length.toInt()).decodeToString() }
    }

    fun writeElevationRange(id: String, json: String) {
        val directory = elevationJobDirectory(id)
        fs.createDirectories(directory)
        val path = checked(Path(directory, "range.json"))
        val temporary = checked(Path(directory, "range.json.part"))
        val bytes = json.encodeToByteArray()
        require(bytes.size <= 65_536)
        requireCapacity(bytes.size.toLong() + 65_536)
        fs.sink(temporary).buffered().use { it.write(bytes) }
        syncPath(temporary.toString(), false)
        fs.atomicMove(temporary, path)
        syncPath(directory.toString(), true)
    }

    fun elevationTilesPath(id: String): Path = checked(Path(elevationJobDirectory(id), "tiles.mbtiles"))

    fun clearElevationTiles(id: String) {
        val directory = elevationJobDirectory(id)
        listOf("tiles.mbtiles", "tiles.mbtiles-journal").forEach {
            fs.delete(checked(Path(directory, it)), mustExist = false)
        }
    }

    fun deleteElevationJob(id: String) {
        val path = elevationJobDirectory(id)
        if (fs.exists(path)) deleteTree(path)
    }

    fun installElevationTiles(id: String, relativePath: String) {
        val source = checked(Path(elevationJobDirectory(id), "tiles.mbtiles"))
        val destination = asset(id, false, relativePath)
        fs.createDirectories(checkNotNull(destination.parent))
        syncPath(source.toString(), false)
        fs.atomicMove(source, destination)
        syncPath(checkNotNull(destination.parent).toString(), true)
        syncPath(elevationJobDirectory(id).toString(), true)
    }

    fun transferDirectory(id: String): Path {
        checkComponent(id)
        return checked(Path(root, "transfers", id))
    }

    fun createTransfer(id: String): Path = transferDirectory(id).also { fs.createDirectories(it, mustCreate = true) }

    fun deleteTransfer(id: String) {
        val path = transferDirectory(id)
        if (fs.exists(path)) deleteTree(path)
    }

    fun clearTransfers() {
        val parent = checked(Path(root, "transfers"))
        if (fs.exists(parent)) fs.list(parent).forEach { deleteTree(checked(it)) }
    }

    fun deleteAsset(id: String, staged: Boolean, relativePath: String) {
        fs.delete(asset(id, staged, relativePath), mustExist = false)
    }

    fun directory(id: String, staged: Boolean): Path {
        checkComponent(id)
        return checked(Path(root, if (staged) "staging" else "ready", id))
    }

    fun create(id: String): Path {
        if (exists(id, false) || exists(id, true)) throw PackageAlreadyExists()
        val path = directory(id, true)
        fs.createDirectories(path, mustCreate = true)
        syncPath(checkNotNull(path.parent).toString(), true)
        return path
    }

    fun exists(id: String, staged: Boolean): Boolean = fs.exists(directory(id, staged))

    fun ids(staged: Boolean): List<String> {
        val parent = checked(Path(root, if (staged) "staging" else "ready"))
        if (!fs.exists(parent)) return emptyList()
        return fs.list(parent).filter { fs.metadataOrNull(checked(it))?.isDirectory == true }
            .map { it.name }.onEach(::checkComponent).sorted()
    }

    fun asset(id: String, staged: Boolean, relativePath: String): Path {
        val parts = relativePath.split('/')
        if (parts.isEmpty() || parts.size > 8) throw UnsafePackagePath()
        parts.forEach(::checkComponent)
        return checked(Path(directory(id, staged), *parts.toTypedArray()))
    }

    fun assetSize(id: String, staged: Boolean, relativePath: String): Long {
        val path = asset(id, staged, relativePath)
        val metadata = fs.metadataOrNull(path) ?: throw FileNotFoundException(relativePath)
        if (!metadata.isRegularFile) throw UnsafePackagePath()
        return metadata.size
    }

    fun size(id: String, staged: Boolean): Long = treeSize(directory(id, staged))

    fun relativeFiles(id: String, staged: Boolean): Set<String> {
        val directory = directory(id, staged)
        fun visit(path: Path): List<String> {
            val metadata = fs.metadataOrNull(checked(path)) ?: throw FileNotFoundException(path.name)
            return when {
                metadata.isRegularFile -> listOf(path.toString().removePrefix("$directory/"))
                metadata.isDirectory -> fs.list(path).flatMap(::visit)
                else -> throw UnsafePackagePath()
            }
        }
        return visit(directory).toSet()
    }

    fun removeTemporaryFiles(id: String, staged: Boolean = true) {
        relativeFiles(id, staged).filter { it.endsWith(".part") || it == "annotations.db.part-journal" }.forEach {
            fs.delete(asset(id, staged, it))
        }
    }

    fun requireCapacity(additionalBytes: Long) {
        require(additionalBytes >= 0)
        if (availableStorageBytes(root.toString()) < additionalBytes) throw StorageCapacityExceeded(additionalBytes)
    }

    fun availableBytes(): Long = availableStorageBytes(root.toString())

    fun enforceLimit(id: String, staged: Boolean, limit: Long): Long {
        val bytes = size(id, staged)
        if (bytes > limit) throw StorageLimitExceeded(limit, bytes)
        return bytes
    }

    fun read(id: String, staged: Boolean, relativePath: String, maxBytes: Int): ByteArray {
        val length = assetSize(id, staged, relativePath)
        if (length > maxBytes) throw StorageLimitExceeded(maxBytes.toLong(), length)
        return fs.source(asset(id, staged, relativePath)).buffered().use { source ->
            val result = source.readByteArray(length.toInt())
            if (!source.exhausted()) throw IOException("File changed during read")
            result
        }
    }

    suspend fun write(id: String, relativePath: String, source: RawSource, maxBytes: Long, packageLimit: Long, staged: Boolean = true) {
        require(maxBytes >= 0 && packageLimit > 0)
        val destination = asset(id, staged, relativePath)
        val temporary = asset(id, staged, "$relativePath.part")
        fs.createDirectories(checkNotNull(destination.parent))
        if (fs.exists(temporary)) fs.delete(temporary)
        val existing = size(id, staged) - if (fs.exists(destination)) assetSize(id, staged, relativePath) else 0L
        var copied = 0L
        try {
            fs.sink(temporary).use { sink ->
                val buffer = Buffer()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.readAtMostTo(buffer, 65_536)
                    if (count == -1L) break
                    if (count == 0L) throw IOException("Source made no progress")
                    if (count > maxBytes - copied) throw StorageLimitExceeded(maxBytes, copied + count)
                    if (count > packageLimit - existing - copied) {
                        throw StorageLimitExceeded(packageLimit, existing + copied + count)
                    }
                    requireCapacity(count + 65_536)
                    sink.write(buffer, count)
                    copied += count
                }
                sink.flush()
            }
            syncPath(temporary.toString(), false)
            fs.atomicMove(temporary, destination)
            syncPath(checkNotNull(destination.parent).toString(), true)
        } finally {
            fs.delete(temporary, mustExist = false)
        }
    }

    fun beginStream(id: String, relativePath: String) {
        val path = asset(id, true, "$relativePath.part")
        fs.createDirectories(checkNotNull(path.parent))
        fs.delete(asset(id, true, relativePath), mustExist = false)
        fs.sink(path).close()
    }

    fun appendStream(id: String, relativePath: String, bytes: ByteArray, count: Int) {
        require(count in 1..bytes.size)
        requireCapacity(count.toLong() + 65_536)
        fs.sink(asset(id, true, "$relativePath.part"), append = true).buffered().use { it.write(bytes, 0, count) }
    }

    fun finishStream(id: String, relativePath: String) {
        val source = asset(id, true, "$relativePath.part")
        val destination = asset(id, true, relativePath)
        syncPath(source.toString(), false)
        fs.atomicMove(source, destination)
        syncPath(checkNotNull(destination.parent).toString(), true)
    }

    fun discardStream(id: String, relativePath: String) {
        fs.delete(asset(id, true, "$relativePath.part"), mustExist = false)
    }

    fun readPrefix(id: String, staged: Boolean, relativePath: String, count: Int): ByteArray {
        val size = assetSize(id, staged, relativePath)
        return fs.source(asset(id, staged, relativePath)).buffered().use { it.readByteArray(minOf(size, count.toLong()).toInt()) }
    }

    fun commitAsset(id: String, temporary: String, destination: String) {
        val source = asset(id, false, temporary)
        val target = asset(id, false, destination)
        syncPath(source.toString(), false)
        fs.atomicMove(source, target)
        syncPath(checkNotNull(target.parent).toString(), true)
    }

    fun promote(id: String) {
        val source = directory(id, true)
        val destination = directory(id, false)
        if (fs.exists(destination)) throw PackageAlreadyExists()
        fs.createDirectories(checkNotNull(destination.parent))
        syncTree(source)
        fs.atomicMove(source, destination)
        syncPath(checkNotNull(destination.parent).toString(), true)
        syncPath(checkNotNull(source.parent).toString(), true)
    }

    fun delete(id: String, staged: Boolean) {
        val path = directory(id, staged)
        if (!fs.exists(path)) return
        deleteTree(path)
        syncPath(checkNotNull(path.parent).toString(), true)
    }

    private fun checked(path: Path): Path {
        var ancestor: Path? = path
        while (ancestor != null && ancestor != root) {
            if (isSymbolicLink(ancestor.toString())) throw UnsafePackagePath()
            if (fs.exists(ancestor) && fs.resolve(ancestor) != ancestor) throw UnsafePackagePath()
            ancestor = ancestor.parent
        }
        if (ancestor != root) throw UnsafePackagePath()
        return path
    }

    private fun treeSize(path: Path): Long {
        val metadata = fs.metadataOrNull(checked(path)) ?: throw FileNotFoundException(path.name)
        if (metadata.isRegularFile) return metadata.size
        if (!metadata.isDirectory) throw UnsafePackagePath()
        return fs.list(path).fold(0L) { total, child ->
            val bytes = treeSize(child)
            if (bytes > Long.MAX_VALUE - total) throw StorageLimitExceeded(Long.MAX_VALUE, Long.MAX_VALUE)
            total + bytes
        }
    }

    private fun syncTree(path: Path) {
        val directory = fs.metadataOrNull(checked(path))?.isDirectory == true
        if (directory) fs.list(path).forEach(::syncTree)
        syncPath(path.toString(), directory)
    }

    private fun deleteTree(path: Path) {
        if (fs.metadataOrNull(checked(path))?.isDirectory == true) fs.list(path).forEach(::deleteTree)
        fs.delete(path)
    }
}

fun checkComponent(value: String) {
    if (value.length !in 1..128 || value in setOf(".", "..") ||
        value.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it !in "._-" }) {
        throw UnsafePackagePath()
    }
}

internal expect fun availableStorageBytes(path: String): Long
internal expect fun syncPath(path: String, directory: Boolean)
internal expect fun isSymbolicLink(path: String): Boolean
