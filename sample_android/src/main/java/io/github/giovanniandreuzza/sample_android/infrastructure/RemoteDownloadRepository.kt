package io.github.giovanniandreuzza.sample_android.infrastructure

import com.giovanniandreuzza.nimbus.core.application.dtos.DownloadStream
import com.giovanniandreuzza.nimbus.core.ports.DownloadRepository
import io.github.giovanniandreuzza.sample_android.framework.retrofit.AppEndpoint
import okio.source
import kotlin.text.toLong

/**
 * Remote Download Repository Adapter.
 *
 * @param appEndpoint App Endpoint.
 * @author Giovanni Andreuzza
 */
class RemoteDownloadRepository(private val appEndpoint: AppEndpoint) : DownloadRepository {

    override suspend fun getFileSize(fileUrl: String): Long {
        return appEndpoint.getFileSize(fileUrl).headers()["Content-Length"]?.toLong() ?: 0
    }

    override suspend fun downloadFile(fileUrl: String, offset: Long?): DownloadStream {
        val range = "bytes=${offset ?: 0}-"
        val fileStream = appEndpoint.downloadApp(fileUrl, range)

        fileStream.body()?.let {
            val fileSize = it.contentLength() + (offset ?: 0)
            return DownloadStream(
                it.byteStream().source(),
                fileSize,
                offset ?: 0
            )
        } ?: throw Exception("File not found")
    }
}