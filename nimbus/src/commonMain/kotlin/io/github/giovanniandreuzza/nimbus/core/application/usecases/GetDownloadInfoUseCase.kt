package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadInfoDTO.Companion.toDomain
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadRequest
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.errors.DownloadInfoNotFound
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadInfoRepository
import io.github.giovanniandreuzza.nimbus.core.states.DownloadState
import io.github.giovanniandreuzza.nimbus.shared.ddd.application.UseCase
import io.github.giovanniandreuzza.nimbus.shared.utils.KResult

/**
 * Get download state use case.
 *
 * @author Giovanni Andreuzza
 */
internal class GetDownloadStateUseCase(
    private val downloadInfoRepository: DownloadInfoRepository
) : UseCase<DownloadRequest, DownloadState, DownloadInfoNotFound> {

    override suspend fun execute(params: DownloadRequest): KResult<DownloadState, DownloadInfoNotFound> {
        val downloadId = DownloadId.create(
            fileUrl = params.fileUrl,
            filePath = params.filePath,
            fileName = params.fileName
        )

        val downloadFile = downloadInfoRepository.getDownloadInfo(downloadId.value)?.toDomain()

        if (downloadFile == null) {
            return KResult.Failure(DownloadInfoNotFound(params.filePath))
        }

        return KResult.Success(downloadFile.state)
    }
}