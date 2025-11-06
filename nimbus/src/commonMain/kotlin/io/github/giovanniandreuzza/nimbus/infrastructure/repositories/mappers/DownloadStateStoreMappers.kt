package io.github.giovanniandreuzza.nimbus.infrastructure.repositories.mappers

import io.github.giovanniandreuzza.explicitarchitecture.infrastructure.mappers.IsInfrastructureMapper
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage.DownloadStateStore

/**
 * Download State Mapper.
 *
 * @author Giovanni Andreuzza
 */
@IsInfrastructureMapper
internal object DownloadStateStoreMappers {

    /**
     * Convert [DownloadStateStore] to [DownloadState].
     *
     * @return [DownloadState] object.
     * @author Giovanni Andreuzza
     */
    internal fun DownloadStateStore.toState(): DownloadState {
        return when (this) {
            is DownloadStateStore.Enqueued -> DownloadState.Enqueued
            is DownloadStateStore.Downloading -> DownloadState.Downloading(progress)
            is DownloadStateStore.Paused -> DownloadState.Paused(progress)
            is DownloadStateStore.Failed -> DownloadState.Failed(toState())

            is DownloadStateStore.Finished -> DownloadState.Finished
        }
    }

    /**
     * Convert [DownloadState] to [DownloadStateStore].
     *
     * @return [DownloadStateStore] object.
     * @author Giovanni Andreuzza
     */
    internal fun DownloadState.toStore(): DownloadStateStore {
        return when (this) {
            is DownloadState.Enqueued -> DownloadStateStore.Enqueued
            is DownloadState.Downloading -> DownloadStateStore.Downloading(progress)
            is DownloadState.Paused -> DownloadStateStore.Paused(progress)
            is DownloadState.Failed -> error.toStore()
            is DownloadState.Finished -> DownloadStateStore.Finished
        }
    }

    /**
     * Convert [DownloadStateStore.Failed] to [KError].
     *
     * @return [KError] object.
     * @author Giovanni Andreuzza
     */
    private fun DownloadStateStore.Failed.toState(): KError {
        return KError(
            code = this.errorCode,
            message = this.errorMessage,
            cause = this.errorCause?.toState()
        )
    }

    /**
     * Convert [KError] to [DownloadStateStore.Failed].
     *
     * @return [DownloadStateStore.Failed] object.
     * @author Giovanni Andreuzza
     */
    private fun KError.toStore(): DownloadStateStore.Failed {
        return DownloadStateStore.Failed(
            errorCode = this.code,
            errorMessage = this.message,
            errorCause = this.cause?.toStore()
        )
    }
}