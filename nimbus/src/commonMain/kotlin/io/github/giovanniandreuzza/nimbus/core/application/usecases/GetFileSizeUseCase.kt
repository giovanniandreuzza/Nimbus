package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.IsUseCase
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.getOr
import io.github.giovanniandreuzza.nimbus.core.application.dtos.GetFileSizeRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.GetFileSizeResponse
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadPort
import io.github.giovanniandreuzza.nimbus.core.queries.GetFileSizeQuery

/**
 * Get file size use case.
 *
 * @param downloadPort The download repository.
 * @author Giovanni Andreuzza
 */
@IsUseCase
internal class GetFileSizeUseCase(
    private val downloadPort: DownloadPort
) : GetFileSizeQuery {

    override suspend fun invoke(request: GetFileSizeRequest): KResult<GetFileSizeResponse, GetFileSizeError> {
        val fileSize = downloadPort.getFileSizeToDownload(request.fileUrl).getOr {
            return Failure(it)
        }
        return Success(GetFileSizeResponse(fileSize))
    }

}