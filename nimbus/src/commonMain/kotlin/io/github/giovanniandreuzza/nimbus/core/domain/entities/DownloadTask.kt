package io.github.giovanniandreuzza.nimbus.core.domain.entities

import io.github.giovanniandreuzza.explicitarchitecture.core.domain.aggregates.AggregateRoot
import io.github.giovanniandreuzza.explicitarchitecture.core.domain.aggregates.IsAggregateRoot
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
    val fileUrl: FileUrl,
    val filePath: FilePath,
    val fileName: FileName,
    val fileSize: FileSize,
    private var _state: DownloadState,
    version: Int
) : AggregateRoot<DownloadId, DownloadTaskEvents>(
    id = DownloadId.create(fileUrl.value, filePath.value, fileName.value),
    version = version
) {

    val state: DownloadState
        get() = _state

    init {
        if (_state is DownloadState.Enqueued && version == 0) {
            enqueueEvent(DownloadTaskEvents.DownloadEnqueuedEvent(entityId, version))
        }
    }

    fun start(): KResult<Unit, StartDownloadErrors> {
        if (state !is DownloadState.Enqueued) {
            return when (state) {
                is DownloadState.Downloading -> Failure(
                    StartDownloadErrors.DownloadAlreadyStarted(
                        entityId.id.value
                    )
                )

                else -> Failure(StartDownloadErrors.DownloadIsNotEnqueued(entityId.id.value))
            }
        }

        val progress = 0.0
        _state = DownloadState.Downloading(progress)
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

    fun fail(errorCode: String, errorMessage: String) {
        _state = DownloadState.Failed(errorCode, errorMessage)
        enqueueEvent(
            DownloadTaskEvents.DownloadFailedEvent(
                entityId,
                version,
                errorCode,
                errorMessage
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
        return "DownloadTask(entityId=$entityId, version=$version, fileUrl=$fileUrl, filePath=$filePath, fileName=$fileName, fileSize=$fileSize, state=$_state)"
    }

    companion object {
        /**
         * Create a new download task.
         *
         * @param fileUrl URL to download.
         * @param filePath Path to save the file.
         * @param fileName file name.
         * @param fileSize file size.
         * @param state download state.
         * @param version aggregate version.
         * @return [DownloadTask] aggregate root.
         */
        fun create(
            fileUrl: String,
            filePath: String,
            fileName: String,
            fileSize: Long,
            state: DownloadState = DownloadState.Enqueued,
            version: Int = 0
        ): DownloadTask {
            val fileUrlVO = FileUrl.create(fileUrl)
            val filePathVO = FilePath.create(filePath)
            val fileNameVO = FileName.create(fileName)
            val fileSizeVO = FileSize.create(fileSize)

            return DownloadTask(
                fileUrl = fileUrlVO,
                filePath = filePathVO,
                fileName = fileNameVO,
                fileSize = fileSizeVO,
                _state = state,
                version = version
            )
        }
    }
}