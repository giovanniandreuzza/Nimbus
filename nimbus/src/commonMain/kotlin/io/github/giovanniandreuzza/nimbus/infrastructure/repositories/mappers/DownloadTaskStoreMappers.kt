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
            checksum = checksum.toChecksum(),
            createdAtEpochMs = createdAtEpochMs,
            finishedAtEpochMs = finishedAtEpochMs,
            resumeValidator = resumeValidator
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
            checksum = checksum.toStore(),
            createdAtEpochMs = createdAtEpochMs,
            finishedAtEpochMs = finishedAtEpochMs,
            resumeValidator = resumeValidator
        )
    }

    private fun Checksum?.toStore(): ChecksumStore? =
        this?.let { ChecksumStore(algorithm = it.algorithm.name, value = it.value) }

    /**
     * A digest this build cannot make sense of is dropped rather than guessed at.
     *
     * Two ways it can happen. The algorithm may be one this build no longer recognises;
     * reporting the value under the wrong algorithm would have every later comparison fail.
     * Or the value itself may be impossible — before 2.5.0 `Checksum`'s constructor took any
     * string, so a store can hold `"abc"` where a digest belongs, and restoring that produces
     * a `ChecksumMismatch` on every transfer for ever, which is the endless re-download this
     * feature exists to end.
     *
     * Dropping it means the task reports no checksum and, if it was an expectation, verifies
     * nothing. That is the lesser of the two: a check that could never have passed is not a
     * check being skipped, and `enqueueDownload` refuses the same value today, so nothing can
     * put another one there.
     */
    private fun ChecksumStore?.toChecksum(): Checksum? {
        val store = this ?: return null
        val algorithm = DigestAlgorithm.entries.firstOrNull { it.name == store.algorithm }
            ?: return null
        return try {
            Checksum.of(algorithm, store.value)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}