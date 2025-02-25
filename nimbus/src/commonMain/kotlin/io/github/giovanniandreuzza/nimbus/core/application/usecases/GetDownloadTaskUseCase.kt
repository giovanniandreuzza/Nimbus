package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.explicitarchitecture.shared.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.isFailure
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.core.queries.GetDownloadTaskQuery

/**
 * Get download task use case.
 *
 * @param downloadTaskRepository The download task repository.
 * @author Giovanni Andreuzza
 */
internal class GetDownloadTaskUseCase(
    private val downloadTaskRepository: DownloadTaskRepository
) : GetDownloadTaskQuery {

    override suspend fun execute(
        params: DownloadRequest
    ): KResult<DownloadTaskDTO, DownloadTaskNotFound> {
        val downloadId = DownloadId.create(
            fileUrl = params.fileUrl,
            filePath = params.filePath,
            fileName = params.fileName
        )

        val downloadFileResult = downloadTaskRepository.getDownloadTask(downloadId.value)

        if (downloadFileResult.isFailure()) {
            return Failure(DownloadTaskNotFound(params.filePath))
        }

        return Success(downloadFileResult.value)
    }

}