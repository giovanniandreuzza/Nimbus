package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.explicitarchitecture.shared.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.events.Event
import io.github.giovanniandreuzza.explicitarchitecture.shared.events.EventBus
import io.github.giovanniandreuzza.explicitarchitecture.shared.isFailure
import io.github.giovanniandreuzza.explicitarchitecture.shared.isSuccess
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO.Companion.toDomain
import io.github.giovanniandreuzza.nimbus.core.application.dtos.GetFileSizeRequest
import io.github.giovanniandreuzza.nimbus.core.commands.EnqueueDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.errors.EnqueueDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.core.queries.GetFileSizeQuery

/**
 * Enqueue Download Use Case.
 *
 * @param eventBus The event bus.
 * @param getFileSizeQuery The get file size query.
 * @param downloadTaskRepository The download task repository.
 * @author Giovanni Andreuzza
 */
internal class EnqueueDownloadUseCase(
    private val eventBus: EventBus<Event>,
    private val getFileSizeQuery: GetFileSizeQuery,
    private val downloadTaskRepository: DownloadTaskRepository
) : EnqueueDownloadCommand {

    override suspend fun execute(
        params: DownloadRequest
    ): KResult<DownloadTaskDTO, EnqueueDownloadErrors> {
        val downloadId = DownloadId.create(
            fileUrl = params.fileUrl,
            filePath = params.filePath,
            fileName = params.fileName
        )

        val downloadTaskResult = downloadTaskRepository.getDownloadTask(downloadId.value)

        if (downloadTaskResult.isSuccess()) {
            when (val state = downloadTaskResult.value.toDomain().state) {
                DownloadState.Enqueued -> Failure(
                    EnqueueDownloadErrors.DownloadAlreadyEnqueued(
                        params
                    )
                )

                is DownloadState.Downloading -> Failure(
                    EnqueueDownloadErrors.DownloadAlreadyStarted(
                        params
                    )
                )

                is DownloadState.Failed -> Failure(
                    EnqueueDownloadErrors.DownloadFailed(
                        state.errorMessage
                    )
                )

                is DownloadState.Paused -> Failure(
                    EnqueueDownloadErrors.DownloadIsPaused(
                        params
                    )
                )

                DownloadState.Finished -> Failure(
                    EnqueueDownloadErrors.DownloadAlreadyCompleted(
                        params
                    )
                )
            }
        }

        val getFileSizeRequest = GetFileSizeRequest(
            fileUrl = params.fileUrl
        )

        val fileSizeResult = getFileSizeQuery.execute(getFileSizeRequest)

        if (fileSizeResult.isFailure()) {
            return Failure(EnqueueDownloadErrors.GetFileSizeFailed(fileSizeResult.error.message))
        }

        val downloadTask = DownloadTask.create(
            fileUrl = params.fileUrl,
            filePath = params.filePath,
            fileName = params.fileName,
            fileSize = fileSizeResult.value.fileSize
        )

        val downloadTaskDTO = DownloadTaskDTO.fromDomain(downloadTask)

        val result = downloadTaskRepository.saveDownloadTask(downloadTaskDTO)

        if (result.isFailure()) {
            return Failure(EnqueueDownloadErrors.DownloadFailed(result.error.message))
        }

        eventBus.publishAll(downloadTask.dequeueEvents())

        return Success(downloadTaskDTO)
    }
}