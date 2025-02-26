package io.github.giovanniandreuzza.nimbus.infrastructure.mappers

import io.github.giovanniandreuzza.explicitarchitecture.infrastructure.mappers.IsInfrastructureMapper
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.frameworks.filemanager.models.DownloadStateStore

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
            is DownloadStateStore.Failed -> DownloadState.Failed(errorCode, errorMessage)
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
            is DownloadState.Failed -> DownloadStateStore.Failed(errorCode, errorMessage)
            is DownloadState.Finished -> DownloadStateStore.Finished
        }
    }
}