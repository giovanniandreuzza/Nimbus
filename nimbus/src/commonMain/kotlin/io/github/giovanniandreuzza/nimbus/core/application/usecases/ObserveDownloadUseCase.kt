package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isFailure
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ObserveDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ObserveDownloadResponse
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.core.queries.ObserveDownloadQuery
import io.github.giovanniandreuzza.nimbus.shared.utils.takeUntil

internal class ObserveDownloadUseCase(
    private val downloadTaskRepository: DownloadTaskRepository
) : ObserveDownloadQuery {

    override suspend fun execute(
        request: ObserveDownloadRequest
    ): KResult<ObserveDownloadResponse, DownloadTaskNotFound> {
        val result = downloadTaskRepository.observeDownloadState(request.downloadId)

        if (result.isFailure()) {
            return result
        }

        val downloadStateFlow = result.value.takeUntil {
            it is DownloadState.Finished || it is DownloadState.Failed
        }

        return Success(ObserveDownloadResponse(downloadStateFlow))
    }
}