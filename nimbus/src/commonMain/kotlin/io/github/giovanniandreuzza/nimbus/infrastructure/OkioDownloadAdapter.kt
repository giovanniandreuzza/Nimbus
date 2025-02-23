package io.github.giovanniandreuzza.nimbus.infrastructure

import io.github.giovanniandreuzza.nimbus.api.DownloadCallback
import io.github.giovanniandreuzza.nimbus.api.FileCallback
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadInfoDTO
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadStream
import io.github.giovanniandreuzza.nimbus.core.errors.DownloadInfoNotFound
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadRepository
import io.github.giovanniandreuzza.nimbus.shared.utils.Either
import io.github.giovanniandreuzza.nimbus.shared.utils.getDownloadProgress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.buffer
import okio.use

/**
 * Download Adapter.
 *
 * @param fileCallback File Callback
 * @param downloadCallback Download Callback
 * @author Giovanni Andreuzza
 */
internal class DownloadAdapter(
    private val dispatcher: CoroutineDispatcher,
    private val fileCallback: FileCallback,
    private val downloadCallback: DownloadCallback
) : DownloadRepository {

    private companion object {
        const val DEFAULT_BUFFER_SIZE: Long = 8 * 1024
    }

    override suspend fun startDownload(
        downloadInfo: DownloadInfoDTO,
        onProgress: (Double) -> Unit
    ): Either<Unit, DownloadInfoNotFound> {
        try {
            val sinkResult = fileCallback.getFileSink(downloadInfo.filePath)

            if (sinkResult is Either.Failure) {
                return Either.Failure(DownloadInfoNotFound(downloadInfo.filePath))
            }

            val sink = (sinkResult as Either.Success).value

            val downloadStream = getDownloadStream(
                downloadInfo.fileUrl,
                downloadInfo.filePath,
                downloadInfo.fileSize
            ) ?: return Either.Success(Unit)

            val source = downloadStream.source
            val contentLength = downloadStream.contentLength
            var progressBytes = downloadStream.downloadedBytes

            withContext(dispatcher) {
                sink.buffer().use { output ->
                    source.buffer().use { input ->
                        while (!input.exhausted()) {
                            ensureActive()

                            val bytesRead = input.read(output.buffer, DEFAULT_BUFFER_SIZE)

                            if (bytesRead > 0) {
                                progressBytes += bytesRead

                                val downloadProgress =
                                    getDownloadProgress(progressBytes, contentLength)

                                onProgress(downloadProgress)

                                output.emit()
                            }
                        }
                    }
                }
            }

            return Either.Success(Unit)
        } catch (_: Exception) {
            return Either.Failure(DownloadInfoNotFound(downloadInfo.filePath))
        }
    }

    /* Private Methods */

    private suspend fun getDownloadStream(
        fileUrl: String,
        filePath: String,
        fileSize: Long
    ): DownloadStream? {
        if (!fileCallback.exists(filePath)) {
            return downloadCallback.downloadFile(fileUrl, 0)
        }

        val bytesAlreadyDownloadedAmount = getBytesAlreadyDownloadedAmount(filePath)

        if (bytesAlreadyDownloadedAmount == fileSize) {
            return null
        }

        return downloadCallback.downloadFile(fileUrl, bytesAlreadyDownloadedAmount)
    }

    private fun getBytesAlreadyDownloadedAmount(filePath: String): Long {
        val fileSizeResult = fileCallback.getFileSize(filePath)

        return when (fileSizeResult) {
            is Either.Failure -> 0
            is Either.Success -> fileSizeResult.value
        }
    }
}