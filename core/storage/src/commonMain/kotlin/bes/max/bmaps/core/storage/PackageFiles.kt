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

    fun removeTemporaryFiles(id: String) {
        relativeFiles(id, true).filter { it.endsWith(".part") }.forEach {
            fs.delete(asset(id, true, it))
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

    suspend fun write(id: String, relativePath: String, source: RawSource, maxBytes: Long, packageLimit: Long) {
        require(maxBytes >= 0 && packageLimit > 0)
        val destination = asset(id, true, relativePath)
        val temporary = asset(id, true, "$relativePath.part")
        fs.createDirectories(checkNotNull(destination.parent))
        if (fs.exists(temporary)) fs.delete(temporary)
        val existing = size(id, true)
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
