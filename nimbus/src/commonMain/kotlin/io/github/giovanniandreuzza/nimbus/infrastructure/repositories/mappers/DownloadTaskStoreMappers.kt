package io.github.giovanniandreuzza.nimbus.infrastructure.repositories.mappers

import io.github.giovanniandreuzza.explicitarchitecture.infrastructure.mappers.IsInfrastructureMapper
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage.ChecksumStore
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage.DownloadTaskStore
import io.github.giovanniandreuzza.nimbus.infrastructure.repositories.mappers.DownloadStateStoreMappers.toState
import io.github.giovanniandreuzza.nimbus.infrastructure.repositories.mappers.DownloadStateStoreMappers.toStore
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm

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
            expectedChecksum = expectedChecksum.toChecksum(),
            checksum = checksum.toChecksum()
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
            expectedChecksum = expectedChecksum.toStore(),
            checksum = checksum.toStore()
        )
    }

    private fun Checksum?.toStore(): ChecksumStore? =
        this?.let { ChecksumStore(algorithm = it.algorithm.name, value = it.value) }

    /**
     * A digest whose algorithm this build no longer recognises is dropped rather than
     * guessed at. The task then reports no checksum, which is the honest answer: reporting
     * one under the wrong algorithm would have every later comparison fail and send the
     * caller into the re-download loop this feature exists to end.
     */
    private fun ChecksumStore?.toChecksum(): Checksum? {
        val store = this ?: return null
        val algorithm = DigestAlgorithm.entries.firstOrNull { it.name == store.algorithm }
            ?: return null
        return Checksum(algorithm, store.value)
    }
}