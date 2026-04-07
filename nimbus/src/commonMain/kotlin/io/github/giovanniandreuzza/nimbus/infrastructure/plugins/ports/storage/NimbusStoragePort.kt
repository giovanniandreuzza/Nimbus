package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.storage

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.CreateFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.DeleteFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.DoesFileExistError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSinkError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.LocalFileSizeError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetUsableSpaceError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSourceError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.MoveFileError
import kotlinx.io.Sink
import kotlinx.io.Source

/**
 * Nimbus Storage Port.
 *
 * @author Giovanni Andreuzza
 */
public interface NimbusStoragePort {
    public fun exists(path: String): KResult<Boolean, DoesFileExistError>
    public fun create(path: String): KResult<Unit, CreateFileError>
    public fun size(path: String): KResult<Long, LocalFileSizeError>

    /**
     * Free bytes on the volume that holds [path] (implementation-defined, usually the parent directory’s filesystem).
     */
    public fun usableSpaceBytes(path: String): KResult<Long, GetUsableSpaceError>
    public fun sink(
        path: String,
        hasToAppend: Boolean
    ): KResult<Sink, GetFileSinkError>

    public fun source(path: String): KResult<Source, GetFileSourceError>
    public fun delete(path: String): KResult<Unit, DeleteFileError>
    public fun atomicMove(sourcePath: String, destinationPath: String): KResult<Unit, MoveFileError>
}
