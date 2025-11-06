package io.github.giovanniandreuzza.nimbus.core.domain.entities

import io.github.giovanniandreuzza.explicitarchitecture.core.domain.aggregates.AggregateRoot
import io.github.giovanniandreuzza.explicitarchitecture.core.domain.aggregates.IsAggregateRoot
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.domain.events.DownloadTaskEvents
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.FileName
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.FilePath
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.FileSize
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.FileUrl
import io.github.giovanniandreuzza.nimbus.core.domain.errors.PauseDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.domain.errors.ResumeDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.domain.errors.StartDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.domain.errors.UpdateDownloadProgressErrors
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState

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
    val fileSize: FileSize,
    private var _state: DownloadState,
    version: Int
) : AggregateRoot<DownloadId, DownloadTaskEvents>(
    id = id,
    version = version
) {

    val state: DownloadState
        get() = _state

    private fun create() {
        enqueueEvent(DownloadTaskEvents.DownloadEnqueuedEvent(entityId, version))
    }

    fun start(): KResult<Unit, StartDownloadErrors> {
        if (state !is DownloadState.Enqueued) {
            return when (state) {
                is DownloadState.Enqueued -> throw IllegalStateException("Unreachable state")

                is DownloadState.Downloading -> Failure(
                    StartDownloadErrors.DownloadAlreadyStarted(
                        entityId.id.value
                    )
                )

                is DownloadState.Paused -> Failure(
                    StartDownloadErrors.DownloadIsPaused(
                        entityId.id.value
                    )
                )

                is DownloadState.Failed -> Failure(
                    StartDownloadErrors.DownloadAlreadyFailed(
                        entityId.id.value
                    )
                )

                is DownloadState.Finished -> Failure(
                    StartDownloadErrors.DownloadAlreadyFinished(
                        entityId.id.value
                    )
                )
            }
        }
        _state = DownloadState.Downloading(0.0)
        enqueueEvent(DownloadTaskEvents.DownloadStartedEvent(entityId, version))
        return Success(Unit)
    }

    fun updateProgress(progress: Double): KResult<Unit, UpdateDownloadProgressErrors> {
        if (state !is DownloadState.Downloading) {
            return Failure(UpdateDownloadProgressErrors.DownloadTaskIsNotDownloading(entityId.id.value))
        }

        if (progress < (state as DownloadState.Downloading).progress) {
            return Failure(
                UpdateDownloadProgressErrors.IncomingProgressIsLowerThanCurrent(
                    entityId.id.value,
                    progress
                )
            )
        }

        _state = DownloadState.Downloading(progress)
        enqueueEvent(DownloadTaskEvents.DownloadProgressUpdatedEvent(entityId, version, progress))
        return Success(Unit)
    }

    fun pause(): KResult<Unit, PauseDownloadErrors> {
        if (state !is DownloadState.Downloading) {
            return when (state) {
                is DownloadState.Paused -> Failure(
                    PauseDownloadErrors.DownloadAlreadyPaused(
                        entityId.id.value
                    )
                )

                else -> Failure(PauseDownloadErrors.DownloadIsNotDownloading(entityId.id.value))
            }
        }

        val currentProgress = (state as DownloadState.Downloading).progress

        _state = DownloadState.Paused(currentProgress)
        enqueueEvent(
            DownloadTaskEvents.DownloadPausedEvent(
                entityId,
                version,
                currentProgress
            )
        )

        return Success(Unit)
    }

    fun resume(): KResult<Unit, ResumeDownloadErrors> {
        if (state !is DownloadState.Paused) {
            return when (state) {
                is DownloadState.Downloading -> Failure(
                    ResumeDownloadErrors.DownloadAlreadyResumed(
                        entityId.id.value
                    )
                )

                else -> Failure(ResumeDownloadErrors.DownloadIsNotPaused(entityId.id.value))
            }
        }

        val currentProgress = (state as DownloadState.Paused).progress

        _state = DownloadState.Downloading(currentProgress)
        enqueueEvent(DownloadTaskEvents.DownloadResumedEvent(entityId, version, currentProgress))
        return Success(Unit)
    }

    fun fail(error: KError) {
        _state = DownloadState.Failed(error)
        enqueueEvent(
            DownloadTaskEvents.DownloadFailedEvent(
                entityId,
                version,
                error
            )
        )
    }

    fun cancel() {
        enqueueEvent(DownloadTaskEvents.DownloadCanceledEvent(entityId, version))
    }

    fun finish() {
        _state = DownloadState.Finished
        enqueueEvent(DownloadTaskEvents.DownloadFinishedEvent(entityId, version))
    }

    override fun toString(): String {
        return "DownloadTask(id=${entityId.id}, version=$version, fileUrl=$fileUrl, filePath=$filePath, fileName=$fileName, fileSize=$fileSize, state=$_state)"
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
            fileSize: Long
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
                version = 0
            ).apply {
                create()
            }
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
         * @param version aggregate version.
         * @return [DownloadTask] aggregate root.
         */
        fun restore(
            id: String,
            fileUrl: String,
            filePath: String,
            fileName: String,
            fileSize: Long,
            state: DownloadState,
            version: Int
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
                version = version
            )
        }
    }
}