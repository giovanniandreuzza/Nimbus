package io.github.giovanniandreuzza.sample_android.infrastructure

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.api.NimbusDownloadRepository
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadStream
import io.github.giovanniandreuzza.nimbus.core.domain.errors.StartDownloadErrors
import io.github.giovanniandreuzza.nimbus.frameworks.downloadmanager.errors.GetFileSizeFailed
import io.github.giovanniandreuzza.sample_android.framework.retrofit.AppEndpoint
import okio.source
import kotlin.text.toLong

/**
 * Remote Download Repository Adapter.
 *
 * @param appEndpoint App Endpoint.
 * @author Giovanni Andreuzza
 */
class RemoteDownloadRepository(private val appEndpoint: AppEndpoint) : NimbusDownloadRepository {

    override suspend fun getFileSize(fileUrl: String): KResult<Long, GetFileSizeFailed> {
        try {
            val fileSize = appEndpoint.getFileSize(fileUrl).headers()["Content-Length"]?.toLong()
            return Success(fileSize ?: 0)
        } catch (e: Exception) {
            return Failure(GetFileSizeFailed(e.message ?: "Content-Length not found"))
        }
    }

    override suspend fun downloadFile(
        fileUrl: String,
        offset: Long?
    ): KResult<DownloadStream, StartDownloadErrors.StartDownloadFailed> {
        try {
            val range = "bytes=${offset ?: 0}-"
            val fileStream = appEndpoint.downloadApp(fileUrl, range)

            return fileStream.body()?.let {
                val fileSize = it.contentLength() + (offset ?: 0)
                val downloadStream = DownloadStream(
                    it.byteStream().source(),
                    fileSize,
                    offset ?: 0
                )
                Success(downloadStream)
            } ?: Failure(StartDownloadErrors.StartDownloadFailed("Download body missing"))
        } catch (e: Exception) {
            return Failure(StartDownloadErrors.StartDownloadFailed(e.message ?: "Download failed"))
        }
    }
}