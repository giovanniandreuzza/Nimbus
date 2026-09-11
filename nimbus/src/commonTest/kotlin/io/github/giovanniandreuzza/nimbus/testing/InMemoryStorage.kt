package io.github.giovanniandreuzza.nimbus.testing

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.CreateFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.DeleteFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.DoesFileExistError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSinkError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSourceError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetUsableSpaceError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.LocalFileSizeError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.MoveFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.storage.NimbusStoragePort
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readByteArray

/**
 * A filesystem held in a map.
 *
 * Every test that needs storage needed a real directory before this existed, which is why
 * the whole suite could only run on the JVM. It also makes the failures this library has to
 * survive — a delete that fails, a volume that fills up, a commit that cannot be written —
 * something a test can ask for, rather than something it has to arrange on a real disk and
 * mostly cannot arrange at all.
 *
 * Not thread-safe by design: tests drive it from a single test dispatcher, and a lock here
 * would hide a scheduling assumption rather than enforce one.
 */
internal class InMemoryStorage : NimbusStoragePort {

    private val files = mutableMapOf<String, ByteArray>()

    /**
     * Answers for the next calls, most recent script wins. A hook returning `null` lets the
     * call through to the real in-memory behaviour, so a test can fail one path and leave
     * every other one working.
     */
    var onCreate: ((path: String) -> KResult<Unit, CreateFileError>?)? = null
    var onDelete: ((path: String) -> KResult<Unit, DeleteFileError>?)? = null
    var onSink: ((path: String) -> KResult<Sink, GetFileSinkError>?)? = null
    var onAtomicMove: ((from: String, to: String) -> KResult<Unit, MoveFileError>?)? = null

    /** Free bytes reported for any path. `null` models a platform that cannot answer. */
    var usableSpace: Long? = Long.MAX_VALUE

    /** Number of store commits, since one [atomicMove] is one full rewrite of the store. */
    var moveCount: Int = 0
        private set

    // -- inspection helpers ------------------------------------------------

    fun write(path: String, bytes: ByteArray) {
        files[path] = bytes
    }

    fun read(path: String): ByteArray? = files[path]

    fun has(path: String): Boolean = files.containsKey(path)

    fun paths(): Set<String> = files.keys.toSet()

    /** Removes a file without consulting [onDelete], to stage what a hook then reports. */
    fun remove(path: String) {
        files.remove(path)
    }

    // -- NimbusStoragePort -------------------------------------------------

    override fun exists(path: String): KResult<Boolean, DoesFileExistError> =
        Success(files.containsKey(path))

    override fun create(path: String): KResult<Unit, CreateFileError> {
        onCreate?.invoke(path)?.let { return it }
        if (files.containsKey(path)) return Failure(CreateFileError.FileAlreadyExists)
        files[path] = ByteArray(0)
        return Success(Unit)
    }

    override fun size(path: String): KResult<Long, LocalFileSizeError> {
        val bytes = files[path] ?: return Failure(LocalFileSizeError.FileNotFound)
        return Success(bytes.size.toLong())
    }

    override fun usableSpaceBytes(path: String): KResult<Long, GetUsableSpaceError> {
        val space = usableSpace ?: return Failure(GetUsableSpaceError.Unsupported)
        return Success(space)
    }

    override fun sink(path: String, hasToAppend: Boolean): KResult<Sink, GetFileSinkError> {
        onSink?.invoke(path)?.let { return it }
        if (!files.containsKey(path)) return Failure(GetFileSinkError.FileNotFound)
        if (!hasToAppend) files[path] = ByteArray(0)
        return Success(AppendingSink(path, this).buffered())
    }

    override fun source(path: String): KResult<Source, GetFileSourceError> {
        val bytes = files[path] ?: return Failure(GetFileSourceError.FileNotFound)
        return Success(Buffer().apply { write(bytes) })
    }

    override fun delete(path: String): KResult<Unit, DeleteFileError> {
        onDelete?.invoke(path)?.let { return it }
        if (files.remove(path) == null) return Failure(DeleteFileError.FileNotFound)
        return Success(Unit)
    }

    override fun atomicMove(
        sourcePath: String,
        destinationPath: String
    ): KResult<Unit, MoveFileError> {
        moveCount++
        onAtomicMove?.invoke(sourcePath, destinationPath)?.let { return it }
        val bytes = files.remove(sourcePath) ?: return Failure(MoveFileError.FileNotFound)
        files[destinationPath] = bytes
        return Success(Unit)
    }

    internal fun append(path: String, bytes: ByteArray) {
        val current = files[path] ?: ByteArray(0)
        files[path] = current + bytes
    }
}

private class AppendingSink(
    private val path: String,
    private val storage: InMemoryStorage
) : RawSink {

    override fun write(source: Buffer, byteCount: Long) {
        storage.append(path, source.readByteArray(byteCount.toInt()))
    }

    override fun flush(): Unit = Unit

    override fun close(): Unit = Unit
}
