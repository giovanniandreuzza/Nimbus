package io.github.giovanniandreuzza.nimbus.core.ports

import io.github.giovanniandreuzza.explicitarchitecture.core.application.ports.IsPort
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError

/**
 * Download Progress Callback.
 *
 * @author Giovanni Andreuzza
 */
@IsPort
internal interface DownloadProgressCallback {

    suspend fun onDownloadProgress(id: String, progress: Double)

    fun onDownloadFailed(id: String, error: DownloadError)

    suspend fun onDownloadFinished(id: String)

}