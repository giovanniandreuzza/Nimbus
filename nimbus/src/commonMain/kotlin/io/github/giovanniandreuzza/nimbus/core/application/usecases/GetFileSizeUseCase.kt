package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.IsUseCase
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isFailure
import io.github.giovanniandreuzza.nimbus.core.application.dtos.GetFileSizeRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.GetFileSizeResponse
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeFailed
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadRepository
import io.github.giovanniandreuzza.nimbus.core.queries.GetFileSizeQuery

/**
 * Get file size use case.
 *
 * @param downloadRepository The download repository.
 * @author Giovanni Andreuzza
 */
@IsUseCase
internal class GetFileSizeUseCase(
    private val downloadRepository: DownloadRepository
) : GetFileSizeQuery {

    override suspend fun execute(
        request: GetFileSizeRequest
    ): KResult<GetFileSizeResponse, GetFileSizeFailed> {
        val fileSizeResult = downloadRepository.getFileSizeToDownload(request.fileUrl)

        if (fileSizeResult.isFailure()) {
            return fileSizeResult
        }

        return Success(GetFileSizeResponse(fileSizeResult.value))
    }

}