package io.github.giovanniandreuzza.nimbus.core.ports

import io.github.giovanniandreuzza.nimbus.core.domain.errors.StartDownloadErrors

/**
 * Download Callback.
 *
 * @author Giovanni Andreuzza
 */
internal interface DownloadCallback {

    suspend fun onDownloadProgress(id: String, progress: Double)

    fun onDownloadFailed(id: String, error: StartDownloadErrors)

    fun onDownloadFinished(id: String)

}