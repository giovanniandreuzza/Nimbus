package io.github.giovanniandreuzza.nimbus.core.domain.entities

import io.github.giovanniandreuzza.explicitarchitecture.core.domain.aggregates.IsAggregateRoot
import io.github.giovanniandreuzza.explicitarchitecture.core.domain.entities.Entity
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.FileName
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.FilePath
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.FileSize
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.FileUrl
import io.github.giovanniandreuzza.nimbus.presentation.Checksum

/**
 * Download Task.
 *
 * @author Giovanni Andreuzza
 */
@IsAggregateRoot
internal class DownloadTask private constructor(
    id: DownloadId,
    val fileUrl: FileUrl,
    val filePath: FilePath,
    val fileName: FileName,
    fileSize: FileSize,
    private var _state: DownloadState,
    /** What the caller said the finished file should hash to, if anything. */
    val expectedChecksum: Checksum?,
    private var _checksum: Checksum?
) : Entity<DownloadId>(id = id) {

    private var storedFileSize: FileSize = fileSize

    val fileSize: FileSize
        get() = storedFileSize

    val state: DownloadState
        get() = _state

    /**
     * What the finished file actually hashed to, once it finished and a digest algorithm
     * was configured. Null otherwise.
     */
    val checksum: Checksum?
        get() = _checksum

    fun start(): Boolean {
        if (state !is DownloadState.Enqueued) return false
        _state = DownloadState.Downloading(0.0)
        return true
    }

    fun updateProgress(progress: Double): Boolean {
        if (state !is DownloadState.Downloading) return false
        if (progress < (state as DownloadState.Downloading).progress) return false
        _state = DownloadState.Downloading(progress)
        return true
    }

    fun pause(): Boolean {
        if (state !is DownloadState.Downloading) return false
        _state = DownloadState.Paused((state as DownloadState.Downloading).progress)
        return true
    }

    fun resume(): Boolean {
        if (state !is DownloadState.Paused) return false
        _state = DownloadState.Downloading((state as DownloadState.Paused).progress)
        return true
    }

    fun fail(error: DownloadError) {
        _state = DownloadState.Failed(error)
    }

    fun cancel() {
        _state = DownloadState.Cancelled
    }

    /**
     * Resets a [DownloadState.Finished] task back to [DownloadState.Enqueued].
     *
     * Used during boot when the finished file is no longer present on disk,
     * allowing the task to be restarted without re-enqueueing.
     */
    fun resetToEnqueued() {
        _state = DownloadState.Enqueued
    }

    /**
     * Resets a [DownloadState.Failed] task to [DownloadState.Enqueued] for a new attempt.
     *
     * @return `false` if the task was not in [DownloadState.Failed].
     */
    fun resetFromFailedToEnqueued(): Boolean {
        if (_state !is DownloadState.Failed) return false
        _state = DownloadState.Enqueued
        return true
    }

    /**
     * Updates the expected remote size (e.g. after [retryFailedDownload] refetched HEAD).
     * Allowed only in [DownloadState.Enqueued], [DownloadState.Paused], or [DownloadState.Failed].
     */
    fun updateExpectedFileSize(newSizeBytes: Long): Boolean {
        if (newSizeBytes <= 0L) return false
        when (_state) {
            DownloadState.Enqueued,
            is DownloadState.Paused,
            is DownloadState.Failed -> {
                storedFileSize = FileSize.create(newSizeBytes)
                return true
            }

            else -> return false
        }
    }

    /**
     * @param checksum what the transferred bytes hashed to, when a digest was computed.
     * Recorded with the transition rather than separately: a task that is Finished and a
     * task whose content is identified are the same fact, and splitting them would allow a
     * window where one is true and the other is not.
     */
    fun finish(checksum: Checksum? = null) {
        _state = DownloadState.Finished
        if (checksum != null) _checksum = checksum
    }

    override fun toString(): String {
        return "DownloadTask(id=${entityId.id}, fileUrl=$fileUrl, filePath=$filePath, fileName=$fileName, fileSize=$fileSize, state=$_state)"
    }

    companion object {
        /**
         * Create a new download task.
         *
         * @param id Download ID.
         * @param fileUrl URL to download.
         * @param filePath Path to save the file.
         * @param fileName file name.
         * @param fileSize file size.
         * @return [DownloadTask] aggregate root.
         */
        fun create(
            id: String,
            fileUrl: String,
            filePath: String,
            fileName: String,
            fileSize: Long,
            expectedChecksum: Checksum? = null
        ): DownloadTask {
            val id = DownloadId.create(id)
            val fileUrl = FileUrl.create(fileUrl)
            val filePath = FilePath.create(filePath)
            val fileName = FileName.create(fileName)
            val fileSize = FileSize.create(fileSize)

            return DownloadTask(
                id = id,
                fileUrl = fileUrl,
                filePath = filePath,
                fileName = fileName,
                fileSize = fileSize,
                _state = DownloadState.Enqueued,
                expectedChecksum = expectedChecksum,
                _checksum = null
            )
        }

        /**
         * Create a new download task.
         *
         * @param id Download ID.
         * @param fileUrl URL to download.
         * @param filePath Path to save the file.
         * @param fileName file name.
         * @param fileSize file size.
         * @param state download state.
         * @return [DownloadTask] aggregate root.
         */
        fun restore(
            id: String,
            fileUrl: String,
            filePath: String,
            fileName: String,
            fileSize: Long,
            state: DownloadState,
            expectedChecksum: Checksum? = null,
            checksum: Checksum? = null
        ): DownloadTask {
            val id = DownloadId.create(id)
            val fileUrl = FileUrl.create(fileUrl)
            val filePath = FilePath.create(filePath)
            val fileName = FileName.create(fileName)
            val fileSize = FileSize.create(fileSize)

            return DownloadTask(
                id = id,
                fileUrl = fileUrl,
                filePath = filePath,
                fileName = fileName,
                fileSize = fileSize,
                _state = state,
                expectedChecksum = expectedChecksum,
                _checksum = checksum
            )
        }
    }
}