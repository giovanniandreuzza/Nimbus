package io.github.giovanniandreuzza.nimbus.infrastructure.ports

import io.github.giovanniandreuzza.explicitarchitecture.infrastructure.adapters.IsAdapter
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.ports.CreateOutcome
import io.github.giovanniandreuzza.nimbus.core.ports.DeleteOutcome
import io.github.giovanniandreuzza.nimbus.core.ports.StoragePort
import io.github.giovanniandreuzza.nimbus.core.ports.StoragePortError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.CreateFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.DeleteFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetUsableSpaceError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.storage.NimbusStoragePort

/**
 * Anti-corruption layer between core and the storage plugin, mirroring what
 * `DownloadAdapter` does for `NimbusDownloadPort`.
 *
 * It is the only thing that turns the plugin's filesystem vocabulary into the four
 * operations and three outcomes core reasons about. Third-party implementors of
 * [NimbusStoragePort] see nothing of this.
 *
 * @author Giovanni Andreuzza
 */
@IsAdapter
internal class StorageAdapter(
    private val nimbusStoragePort: NimbusStoragePort
) : StoragePort {

    override fun size(path: String): KResult<Long, StoragePortError> =
        when (val result = nimbusStoragePort.size(path)) {
            is Success -> Success(result.value)
            is Failure -> Failure(StoragePortError(result.error))
        }

    override fun create(path: String): KResult<CreateOutcome, StoragePortError> =
        when (val result = nimbusStoragePort.create(path)) {
            is Success -> Success(CreateOutcome.Created)
            is Failure -> when (result.error) {
                is CreateFileError.FileAlreadyExists -> Success(CreateOutcome.AlreadyExists)
                else -> Failure(StoragePortError(result.error))
            }
        }

    override fun delete(path: String): KResult<DeleteOutcome, StoragePortError> =
        when (val result = nimbusStoragePort.delete(path)) {
            is Success -> Success(DeleteOutcome.Deleted)
            is Failure -> when (result.error) {
                is DeleteFileError.FileNotFound -> Success(DeleteOutcome.NotFound)
                else -> Failure(StoragePortError(result.error))
            }
        }

    /**
     * A platform that cannot report free space is not failing — it is answering "I don't
     * know", which is what `null` means here. Core skips the headroom check rather than
     * refusing the download.
     */
    override fun usableSpaceBytes(path: String): KResult<Long?, StoragePortError> =
        when (val result = nimbusStoragePort.usableSpaceBytes(path)) {
            is Success -> Success(result.value)
            is Failure -> when (result.error) {
                is GetUsableSpaceError.Unsupported -> Success(null)
                else -> Failure(StoragePortError(result.error))
            }
        }
}
