package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.storage

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.CreateFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.DeleteFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.DoesFileExistError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSinkError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSourceError
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
    public fun size(path: String): KResult<Long, GetFileSizeError>
    public fun sink(
        path: String,
        hasToAppend: Boolean
    ): KResult<Sink, GetFileSinkError>

    public fun source(path: String): KResult<Source, GetFileSourceError>
    public fun delete(path: String): KResult<Unit, DeleteFileError>
}
