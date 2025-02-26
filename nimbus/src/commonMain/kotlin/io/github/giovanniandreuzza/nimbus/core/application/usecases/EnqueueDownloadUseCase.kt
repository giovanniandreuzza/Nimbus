package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.IsUseCase
import io.github.giovanniandreuzza.explicitarchitecture.core.domain.events.DomainEvent
import io.github.giovanniandreuzza.explicitarchitecture.shared.events.EventBus
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isFailure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isSuccess
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
 * @param domainEventBus The event bus.
 * @param getFileSizeQuery The get file size query.
 * @param downloadTaskRepository The download task repository.
 * @author Giovanni Andreuzza
 */
@IsUseCase
internal class EnqueueDownloadUseCase(
    private val domainEventBus: EventBus<DomainEvent<DownloadId>>,
    private val getFileSizeQuery: GetFileSizeQuery,
    private val downloadTaskRepository: DownloadTaskRepository
) : EnqueueDownloadCommand {

    override suspend fun execute(
        request: DownloadRequest
    ): KResult<DownloadTaskDTO, EnqueueDownloadErrors> {
        val downloadId = DownloadId.create(
            fileUrl = request.fileUrl,
            filePath = request.filePath,
            fileName = request.fileName
        )

        val downloadTaskResult = downloadTaskRepository.getDownloadTask(downloadId.value)

        if (downloadTaskResult.isSuccess()) {
            when (val state = downloadTaskResult.value.toDomain().state) {
                DownloadState.Enqueued -> Failure(
                    EnqueueDownloadErrors.DownloadAlreadyEnqueued(
                        request
                    )
                )

                is DownloadState.Downloading -> Failure(
                    EnqueueDownloadErrors.DownloadAlreadyStarted(
                        request
                    )
                )

                is DownloadState.Failed -> Failure(
                    EnqueueDownloadErrors.DownloadFailed(
                        state.errorMessage
                    )
                )

                is DownloadState.Paused -> Failure(
                    EnqueueDownloadErrors.DownloadIsPaused(
                        request
                    )
                )

                DownloadState.Finished -> Failure(
                    EnqueueDownloadErrors.DownloadAlreadyCompleted(
                        request
                    )
                )
            }
        }

        val getFileSizeRequest = GetFileSizeRequest(
            fileUrl = request.fileUrl
        )

        val fileSizeResult = getFileSizeQuery.execute(getFileSizeRequest)

        if (fileSizeResult.isFailure()) {
            return Failure(EnqueueDownloadErrors.GetFileSizeFailed(fileSizeResult.error.message))
        }

        val downloadTask = DownloadTask.create(
            fileUrl = request.fileUrl,
            filePath = request.filePath,
            fileName = request.fileName,
            fileSize = fileSizeResult.value.fileSize
        )

        val downloadTaskDTO = DownloadTaskDTO.fromDomain(downloadTask)

        val result = downloadTaskRepository.saveDownloadTask(downloadTaskDTO)

        if (result.isFailure()) {
            return Failure(EnqueueDownloadErrors.DownloadFailed(result.error.message))
        }

        domainEventBus.publishAll(downloadTask.dequeueEvents())

        return Success(downloadTaskDTO)
    }
}