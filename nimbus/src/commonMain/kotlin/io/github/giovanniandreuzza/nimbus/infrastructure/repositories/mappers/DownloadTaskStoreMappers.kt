package io.github.giovanniandreuzza.nimbus.infrastructure.repositories.mappers

import io.github.giovanniandreuzza.explicitarchitecture.infrastructure.mappers.IsInfrastructureMapper
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage.DownloadTaskStore
import io.github.giovanniandreuzza.nimbus.infrastructure.repositories.mappers.DownloadStateStoreMappers.toState
import io.github.giovanniandreuzza.nimbus.infrastructure.repositories.mappers.DownloadStateStoreMappers.toStore

/**
 * Download Task Store Mappers.
 *
 * @author Giovanni Andreuzza
 */
@IsInfrastructureMapper
internal object DownloadTaskStoreMappers {
    /**
     * Convert [DownloadTaskStore] to [DownloadTask].
     *
     * @return [DownloadTask] object.
     * @author Giovanni Andreuzza
     */
    internal fun DownloadTaskStore.toDomain(): DownloadTask {
        return DownloadTask.restore(
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
     * Convert [Map] of [String]-[DownloadTaskStore] to [Map] of [DownloadId]-[DownloadTask].
     *
     * @return [Map] of [String]-[DownloadTask].
     * @author Giovanni Andreuzza
     */
    internal fun Map<String, DownloadTaskStore>.toDomains(): Map<DownloadId, DownloadTask> {
        return map {
            it.value.toDomain()
        }.associateBy { it.entityId.id }
    }

    /**
     * Convert [DownloadTask] to [DownloadTaskStore].
     *
     * @return [DownloadTaskStore] object.
     * @author Giovanni Andreuzza
     */
    internal fun DownloadTask.toStore(): DownloadTaskStore {
        return DownloadTaskStore(
            id = entityId.id.value,
            fileName = fileName.value,
            fileUrl = fileUrl.value,
            filePath = filePath.value,
            fileSize = fileSize.value,
            state = state.toStore(),
            version = version
        )
    }
}