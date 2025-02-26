package io.github.giovanniandreuzza.nimbus.core.domain.errors

import io.github.giovanniandreuzza.explicitarchitecture.core.domain.errors.IsDomainError
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Update Download Progress Errors.
 *
 * @param code Error code.
 * @param message Error message.
 * @author Giovanni Andreuzza
 */
@IsDomainError
public sealed class UpdateDownloadProgressErrors(
    override val code: String,
    override val message: String
) : KError(code, message) {

    /**
     * Download Task Is Not Downloading.
     *
     * @param downloadId The download ID.
     */
    @IsDomainError
    public data class DownloadTaskIsNotDownloading(
        val downloadId: String
    ) : UpdateDownloadProgressErrors(
        code = "download_task_is_not_downloading",
        message = "Download Task Is Not Downloading. Download ID: $downloadId"
    )

    /**
     * Incoming Progress Is Lower Than Current.
     *
     * @param downloadId The download ID.
     * @param progress The progress.
     */
    @IsDomainError
    public data class IncomingProgressIsLowerThanCurrent(
        val downloadId: String,
        val progress: Double
    ) : UpdateDownloadProgressErrors(
        code = "incoming_progress_is_lower_than_current",
        message = "Incoming progress is lower than current. Download ID: $downloadId - Wrong progress: $progress"
    )
}