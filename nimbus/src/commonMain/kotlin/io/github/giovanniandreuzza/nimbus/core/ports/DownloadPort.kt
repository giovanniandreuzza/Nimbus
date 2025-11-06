package io.github.giovanniandreuzza.nimbus.core.ports

import io.github.giovanniandreuzza.explicitarchitecture.core.application.ports.IsPort
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError

/**
 * Download Repository.
 *
 * @author Giovanni Andreuzza
 */
@IsPort
internal interface DownloadPort {

    suspend fun getFileSizeToDownload(fileUrl: String): KResult<Long, GetFileSizeError>

    suspend fun startDownload(downloadTask: DownloadTaskDTO): KResult<Unit, DownloadError>

    fun stopDownload(downloadId: String)

}