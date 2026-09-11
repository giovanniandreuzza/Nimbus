package io.github.giovanniandreuzza.nimbus.core.ports

import io.github.giovanniandreuzza.explicitarchitecture.core.application.ports.IsPort
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.presentation.Checksum

/**
 * Download Progress Callback.
 *
 * @author Giovanni Andreuzza
 */
@IsPort
internal interface DownloadProgressCallback {

    suspend fun onDownloadProgress(id: String, progress: Double)

    suspend fun onDownloadFailed(id: String, error: DownloadError)

    /**
     * @param checksum what the transferred bytes hashed to, or null when no digest
     * algorithm is configured.
     */
    suspend fun onDownloadFinished(id: String, checksum: Checksum? = null)

}