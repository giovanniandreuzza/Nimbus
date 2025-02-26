package io.github.giovanniandreuzza.nimbus.infrastructure.mappers

import io.github.giovanniandreuzza.explicitarchitecture.infrastructure.mappers.IsInfrastructureMapper
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.infrastructure.mappers.DownloadStateStoreMappers.toState
import io.github.giovanniandreuzza.nimbus.infrastructure.mappers.DownloadStateStoreMappers.toStore
import io.github.giovanniandreuzza.nimbus.frameworks.filemanager.models.DownloadTaskStore

/**
 * Download Task Store Mappers.
 *
 * @author Giovanni Andreuzza
 */
@IsInfrastructureMapper
internal object DownloadTaskStoreMappers {
    /**
     * Convert [DownloadTaskStore] to [DownloadTaskDTO].
     *
     * @return [DownloadTaskDTO] object.
     * @author Giovanni Andreuzza
     */
    internal fun DownloadTaskStore.toDTO(): DownloadTaskDTO {
        return DownloadTaskDTO(
            id = id,
            fileName = fileName,
            fileUrl = fileUrl,
            filePath = filePath,
            fileSize = fileSize,
            state = state.toState(),
            version = version
        )
    }

    /**
     * Convert [DownloadTaskDTO] to [DownloadTaskStore].
     *
     * @return [DownloadTaskStore] object.
     * @author Giovanni Andreuzza
     */
    internal fun DownloadTaskDTO.toStore(): DownloadTaskStore {
        return DownloadTaskStore(
            id = id,
            fileName = fileName,
            fileUrl = fileUrl,
            filePath = filePath,
            fileSize = fileSize,
            state = state.toStore(),
            version = version
        )
    }
}