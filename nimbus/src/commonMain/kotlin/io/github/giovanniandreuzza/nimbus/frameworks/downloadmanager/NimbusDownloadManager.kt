package io.github.giovanniandreuzza.nimbus.frameworks.downloadmanager

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.IsFramework
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadStream
import io.github.giovanniandreuzza.nimbus.core.domain.errors.StartDownloadErrors
import io.github.giovanniandreuzza.nimbus.frameworks.downloadmanager.errors.GetFileSizeFailed
import io.github.giovanniandreuzza.nimbus.frameworks.downloadmanager.ports.NimbusDownloadRepository

/**
 * Nimbus Download Manager.
 *
 * @param nimbusDownloadRepository The Nimbus Download Repository.
 * @author Giovanni Andreuzza
 */
@IsFramework
internal class NimbusDownloadManager(
    private val nimbusDownloadRepository: NimbusDownloadRepository
) {

    suspend fun getFileSize(fileUrl: String): KResult<Long, GetFileSizeFailed> {
        return nimbusDownloadRepository.getFileSize(fileUrl)
    }

    suspend fun downloadFile(
        fileUrl: String,
        offset: Long?
    ): KResult<DownloadStream, StartDownloadErrors.StartDownloadFailed> {
        return nimbusDownloadRepository.downloadFile(fileUrl, offset)
    }

}