package io.github.giovanniandreuzza.nimbus.infrastructure.repositories.mappers

import io.github.giovanniandreuzza.explicitarchitecture.infrastructure.mappers.IsInfrastructureMapper
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.PermanentDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage.DownloadStateStore

/**
 * Download State Mapper.
 *
 * @author Giovanni Andreuzza
 */
@IsInfrastructureMapper
internal object DownloadStateStoreMappers {

    internal fun DownloadStateStore.toState(): DownloadState {
        return when (this) {
            is DownloadStateStore.Enqueued -> DownloadState.Enqueued
            is DownloadStateStore.Downloading -> DownloadState.Downloading(progress)
            is DownloadStateStore.Paused -> DownloadState.Paused(progress)
            is DownloadStateStore.Failed -> DownloadState.Failed(toDownloadError())
            is DownloadStateStore.Finished -> DownloadState.Finished
            is DownloadStateStore.Cancelled -> DownloadState.Cancelled
        }
    }

    internal fun DownloadState.toStore(): DownloadStateStore {
        return when (this) {
            is DownloadState.Enqueued -> DownloadStateStore.Enqueued
            is DownloadState.Downloading -> DownloadStateStore.Downloading(progress)
            is DownloadState.Paused -> DownloadStateStore.Paused(progress)
            is DownloadState.Failed -> error.toStore()
            is DownloadState.Finished -> DownloadStateStore.Finished
            is DownloadState.Cancelled -> DownloadStateStore.Cancelled
        }
    }

    /**
     * Reconstruct a [DownloadError] from a persisted [DownloadStateStore.Failed].
     *
     * The outer [DownloadStateStore.Failed.errorCode] selects `TemporaryError` or
     * `PermanentError`; the nested [DownloadStateStore.Failed.errorCause] is used to
     * reconstruct the typed [TemporaryDownloadErrorCause] / [PermanentDownloadErrorCause]. Unknown or
     * legacy codes fall back to [PermanentDownloadErrorCause.UnexpectedError] so that old tasks
     * stored by previous library versions are always readable.
     */
    private fun DownloadStateStore.Failed.toDownloadError(): DownloadError {
        return when (this.errorCode) {
            "TEMPORARY_ERROR" -> DownloadError.TemporaryError(
                this.errorCause?.toTemporaryErrorCause()
                    ?: TemporaryDownloadErrorCause.FileNotAccessible
            )

            "PERMANENT_ERROR" -> DownloadError.PermanentError(
                this.errorCause?.toPermanentErrorCause()
                    ?: PermanentDownloadErrorCause.UnexpectedError()
            )

            else -> DownloadError.PermanentError(
                PermanentDownloadErrorCause.UnexpectedError(toKError())
            )
        }
    }

    private fun DownloadStateStore.Failed.toTemporaryErrorCause(): TemporaryDownloadErrorCause =
        when (this.errorCode) {
            "server_error" -> TemporaryDownloadErrorCause.ServerError(this.errorMessage.parseStatusCode())
            "range_not_satisfiable" -> TemporaryDownloadErrorCause.RangeNotSatisfiable
            "file_integrity_mismatch" -> TemporaryDownloadErrorCause.FileIntegrityMismatch
            "file_not_accessible" -> TemporaryDownloadErrorCause.FileNotAccessible
            "truncate_race" -> TemporaryDownloadErrorCause.TruncateRace
            "checksum_mismatch" -> TemporaryDownloadErrorCause.ChecksumMismatch
            "transport_failure" -> TemporaryDownloadErrorCause.TransportFailure(
                this.errorCause?.toKError() ?: toKError()
            )

            else -> TemporaryDownloadErrorCause.FileNotAccessible
        }

    private fun DownloadStateStore.Failed.toPermanentErrorCause(): PermanentDownloadErrorCause =
        when (this.errorCode) {
            "resource_not_found" -> PermanentDownloadErrorCause.ResourceNotFound
            "client_error" -> PermanentDownloadErrorCause.ClientError(this.errorMessage.parseStatusCode())
            "inconsistent_range_response" -> PermanentDownloadErrorCause.InconsistentRangeResponse(
                this.errorMessage
            )

            "local_file_oversized" -> PermanentDownloadErrorCause.LocalFileOversized
            "insufficient_disk_space" -> PermanentDownloadErrorCause.InsufficientDiskSpace(
                this.errorCause?.toKError() ?: toKError()
            )

            "storage_error" -> PermanentDownloadErrorCause.StorageError(
                this.errorCause?.toKError() ?: toKError()
            )

            else -> PermanentDownloadErrorCause.UnexpectedError(toKError())
        }

    private fun DownloadStateStore.Failed.toKError(): KError =
        KError(
            code = this.errorCode,
            message = this.errorMessage,
            cause = this.errorCause?.toKError()
        )

    private fun KError.toStore(): DownloadStateStore.Failed =
        DownloadStateStore.Failed(
            errorCode = this.code,
            errorMessage = this.message,
            errorCause = this.cause?.toStore()
        )

    /** Extracts a trailing integer status code from messages like "HTTP server error 503." */
    private fun String.parseStatusCode(): Int =
        trimEnd('.').split(" ").lastOrNull()?.toIntOrNull() ?: 0
}