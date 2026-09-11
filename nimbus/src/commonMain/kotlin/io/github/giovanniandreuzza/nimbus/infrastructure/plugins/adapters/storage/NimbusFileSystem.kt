package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.adapters.storage

import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.files.FileMetadata
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * The filesystem operations [FileSystemNimbusStorageAdapter] needs.
 *
 * `kotlinx.io.files.FileSystem` is sealed, so it cannot be implemented outside its own
 * module and cannot be substituted in a test. That leaves the adapter's most consequential
 * behaviour — how it classifies a failure it did not anticipate — impossible to exercise,
 * which is how every catch-all in it came to report unrelated failures as permission
 * denials and stayed that way through review. This interface exists to make that branch
 * reachable.
 *
 * Production always uses [SystemNimbusFileSystem].
 */
internal interface NimbusFileSystem {
    fun exists(path: Path): Boolean
    fun delete(path: Path, mustExist: Boolean = true)
    fun createDirectories(path: Path)
    fun atomicMove(source: Path, destination: Path)
    fun source(path: Path): RawSource
    fun sink(path: Path, append: Boolean): RawSink
    fun metadataOrNull(path: Path): FileMetadata?
}

internal object SystemNimbusFileSystem : NimbusFileSystem {
    override fun exists(path: Path): Boolean = SystemFileSystem.exists(path)

    override fun delete(path: Path, mustExist: Boolean): Unit =
        SystemFileSystem.delete(path, mustExist)

    override fun createDirectories(path: Path): Unit = SystemFileSystem.createDirectories(path)

    override fun atomicMove(source: Path, destination: Path): Unit =
        SystemFileSystem.atomicMove(source, destination)

    override fun source(path: Path): RawSource = SystemFileSystem.source(path)

    override fun sink(path: Path, append: Boolean): RawSink = SystemFileSystem.sink(path, append)

    override fun metadataOrNull(path: Path): FileMetadata? = SystemFileSystem.metadataOrNull(path)
}
