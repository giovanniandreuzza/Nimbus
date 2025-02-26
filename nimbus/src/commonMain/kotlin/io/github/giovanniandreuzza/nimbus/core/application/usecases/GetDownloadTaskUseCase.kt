package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.IsUseCase
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isFailure
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.core.queries.GetDownloadTaskQuery

/**
 * Get download task use case.
 *
 * @param downloadTaskRepository The download task repository.
 * @author Giovanni Andreuzza
 */
@IsUseCase
internal class GetDownloadTaskUseCase(
    private val downloadTaskRepository: DownloadTaskRepository
) : GetDownloadTaskQuery {

    override suspend fun execute(
        request: DownloadRequest
    ): KResult<DownloadTaskDTO, DownloadTaskNotFound> {
        val downloadId = DownloadId.create(
            fileUrl = request.fileUrl,
            filePath = request.filePath,
            fileName = request.fileName
        )

        val downloadFileResult = downloadTaskRepository.getDownloadTask(downloadId.value)

        if (downloadFileResult.isFailure()) {
            return downloadFileResult
        }

        return Success(downloadFileResult.value)
    }

}