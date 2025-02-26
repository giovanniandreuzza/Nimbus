package io.github.giovanniandreuzza.nimbus.core.ports

import io.github.giovanniandreuzza.explicitarchitecture.core.application.ports.IsPort
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeFailed

/**
 * Download Repository.
 *
 * @author Giovanni Andreuzza
 */
@IsPort
internal interface DownloadRepository {

    suspend fun getFileSizeToDownload(fileUrl: String): KResult<Long, GetFileSizeFailed>

    fun startDownload(downloadTask: DownloadTaskDTO): Boolean

    fun stopDownload(downloadId: String)

}