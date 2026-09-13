package io.github.giovanniandreuzza.nimbus.core.ports

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError

internal interface DownloadPort {
    suspend fun getFileSizeToDownload(fileUrl: String): KResult<Long, GetFileSizeError>
    suspend fun startDownload(downloadTask: DownloadTaskDTO): KResult<Unit, DownloadError>
    suspend fun stopDownload(downloadId: String)

    /**
     * Stops every transfer in flight and waits for each to unwind.
     *
     * For shutdown: once this returns, nothing is writing to a file or reporting a state, so
     * whatever is committed after it is what the next boot will read.
     */
    suspend fun stopAllDownloads()
}
