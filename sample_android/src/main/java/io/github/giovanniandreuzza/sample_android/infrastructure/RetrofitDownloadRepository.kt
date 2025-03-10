package io.github.giovanniandreuzza.sample_android.infrastructure

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.frameworks.downloadmanager.ports.NimbusDownloadRepository
import io.github.giovanniandreuzza.nimbus.core.domain.errors.StartDownloadErrors
import io.github.giovanniandreuzza.nimbus.frameworks.downloadmanager.errors.GetFileSizeFailed
import io.github.giovanniandreuzza.sample_android.framework.retrofit.AppEndpoint
import okio.Source
import okio.source
import kotlin.text.toLong

/**
 * Remote Download Repository Adapter.
 *
 * @param appEndpoint App Endpoint.
 * @author Giovanni Andreuzza
 */
class RetrofitDownloadRepository(private val appEndpoint: AppEndpoint) : NimbusDownloadRepository {

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
    ): KResult<Source, StartDownloadErrors.StartDownloadFailed> {
        try {
            val range = "bytes=${offset ?: 0}-"
            val fileStream = appEndpoint.downloadApp(fileUrl, range)

            return fileStream.body()?.let {
                val source = it.byteStream().source()
                Success(source)
            } ?: Failure(StartDownloadErrors.StartDownloadFailed("Download body missing"))
        } catch (e: Exception) {
            return Failure(StartDownloadErrors.StartDownloadFailed(e.message ?: "Download failed"))
        }
    }
}