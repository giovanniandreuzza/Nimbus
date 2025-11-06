package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.IsUseCase
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.getOr
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onFailure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onSuccess
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.dtos.EnqueueDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.GetFileSizeRequest
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.core.commands.EnqueueDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.errors.EnqueueDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.domain.errors.EnqueueDownloadErrors.ConnectionError
import io.github.giovanniandreuzza.nimbus.core.domain.errors.EnqueueDownloadErrors.DownloadAlreadyCompleted
import io.github.giovanniandreuzza.nimbus.core.domain.errors.EnqueueDownloadErrors.DownloadAlreadyEnqueued
import io.github.giovanniandreuzza.nimbus.core.domain.errors.EnqueueDownloadErrors.DownloadAlreadyStarted
import io.github.giovanniandreuzza.nimbus.core.domain.errors.EnqueueDownloadErrors.DownloadFailed
import io.github.giovanniandreuzza.nimbus.core.domain.errors.EnqueueDownloadErrors.DownloadIsPaused
import io.github.giovanniandreuzza.nimbus.core.domain.errors.EnqueueDownloadErrors.PermanentError
import io.github.giovanniandreuzza.nimbus.core.domain.errors.EnqueueDownloadErrors.ResourceNotFound
import io.github.giovanniandreuzza.nimbus.core.domain.errors.EnqueueDownloadErrors.TemporaryError
import io.github.giovanniandreuzza.nimbus.core.domain.errors.EnqueueDownloadErrors.UnexpectedError
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.core.ports.IdProviderPort
import io.github.giovanniandreuzza.nimbus.core.queries.GetFileSizeQuery

/**
 * Enqueue Download Use Case.
 *
 * @param idProviderPort The id provider port.
 * @param getFileSizeQuery The get file size query.
 * @param downloadTaskRepository The download task repository.
 * @author Giovanni Andreuzza
 */
@IsUseCase
internal class EnqueueDownloadUseCase(
    private val idProviderPort: IdProviderPort,
    private val getFileSizeQuery: GetFileSizeQuery,
    private val downloadTaskRepository: DownloadTaskRepository
) : EnqueueDownloadCommand {

    override suspend fun invoke(
        request: EnqueueDownloadRequest
    ): KResult<DownloadTaskDTO, EnqueueDownloadErrors> {
        val id = idProviderPort.generateUniqueId(request.fileUrl)

        downloadTaskRepository.getDownloadTask(DownloadId.create(id)).onSuccess {
            when (val state = it.state) {
                DownloadState.Enqueued -> Failure(DownloadAlreadyEnqueued)

                is DownloadState.Downloading -> Failure(DownloadAlreadyStarted)

                is DownloadState.Paused -> Failure(DownloadIsPaused)

                is DownloadState.Failed -> Failure(DownloadFailed(state.error))

                DownloadState.Finished -> Failure(DownloadAlreadyCompleted)
            }
        }

        val getFileSizeRequest = GetFileSizeRequest(fileUrl = request.fileUrl)

        val fileSizeResponse = getFileSizeQuery(getFileSizeRequest).getOr {
            return Failure(
                when (it) {
                    is GetFileSizeError.TemporaryError -> TemporaryError(it.cause)
                    GetFileSizeError.ResourceNotFound -> ResourceNotFound
                    is GetFileSizeError.PermanentError -> PermanentError(it.cause)
                    is GetFileSizeError.UnexpectedError -> UnexpectedError(it.cause)
                }
            )
        }

        val downloadTask = DownloadTask.create(
            id = id,
            fileUrl = request.fileUrl,
            filePath = request.filePath,
            fileName = request.fileName,
            fileSize = fileSizeResponse.fileSize
        )

        downloadTaskRepository.saveDownloadTask(downloadTask).onFailure {
            return Failure(DownloadFailed(it))
        }

        val downloadTaskDTO = DownloadTaskDTO.fromDomain(downloadTask)
        return Success(downloadTaskDTO)
    }
}