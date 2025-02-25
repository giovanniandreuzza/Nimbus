package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.explicitarchitecture.shared.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.isFailure
import io.github.giovanniandreuzza.nimbus.api.NimbusDownloadRepository
import io.github.giovanniandreuzza.nimbus.core.application.dtos.GetFileSizeRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.GetFileSizeResponse
import io.github.giovanniandreuzza.nimbus.core.errors.GetFileSizeFailed
import io.github.giovanniandreuzza.nimbus.core.queries.GetFileSizeQuery

/**
 * Get file size use case.
 *
 * @param nimbusDownloadRepository The nimbus download repository.
 * @author Giovanni Andreuzza
 */
internal class GetFileSizeUseCase(
    private val nimbusDownloadRepository: NimbusDownloadRepository
) : GetFileSizeQuery {

    override suspend fun execute(
        params: GetFileSizeRequest
    ): KResult<GetFileSizeResponse, GetFileSizeFailed> {
        val fileSizeResult = nimbusDownloadRepository.getFileSize(params.fileUrl)

        if (fileSizeResult.isFailure()) {
            return fileSizeResult
        }

        return Success(GetFileSizeResponse(fileSizeResult.value))
    }

}