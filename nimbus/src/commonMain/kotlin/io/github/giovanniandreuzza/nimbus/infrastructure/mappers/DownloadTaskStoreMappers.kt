package io.github.giovanniandreuzza.nimbus.infrastructure.mappers

import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.infrastructure.models.DownloadTaskStore

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