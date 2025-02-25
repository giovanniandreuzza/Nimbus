package io.github.giovanniandreuzza.nimbus.core.ports

import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO

/**
 * Download Repository.
 *
 * @author Giovanni Andreuzza
 */
internal interface DownloadRepository {

    fun startDownload(downloadTask: DownloadTaskDTO): Boolean

    fun stopDownload(downloadId: String)

}