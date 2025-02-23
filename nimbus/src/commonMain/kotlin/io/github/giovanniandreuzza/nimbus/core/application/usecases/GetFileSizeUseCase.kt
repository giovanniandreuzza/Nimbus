package io.github.giovanniandreuzza.nimbus.core.usecases

import io.github.giovanniandreuzza.nimbus.core.ports.DownloadRepository
import io.github.giovanniandreuzza.nimbus.shared.ddd.application.UseCase
import io.github.giovanniandreuzza.nimbus.shared.utils.BaseError
import io.github.giovanniandreuzza.nimbus.shared.utils.Either

internal class GetFileSizeUseCase(
    private val downloadRepository: DownloadRepository
) : UseCase<String, Long, BaseError> {

    override suspend fun execute(params: String): Either<Long, BaseError> {
        return try {
            val fileSize = downloadRepository.getFileSize(params)
            Either.Success(fileSize)
        } catch (e: Exception) {
            Either.Failure(BaseError("", "", e))
        }
    }

}