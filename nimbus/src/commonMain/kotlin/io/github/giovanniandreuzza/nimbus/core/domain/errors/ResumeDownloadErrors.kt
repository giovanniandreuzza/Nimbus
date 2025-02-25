package io.github.giovanniandreuzza.nimbus.core.domain.errors

import io.github.giovanniandreuzza.explicitarchitecture.shared.KError

/**
 * Resume Download Errors.
 *
 * @param code Error code.
 * @param message Error message.
 * @author Giovanni Andreuzza
 */
public sealed class ResumeDownloadErrors(
    override val code: String,
    override val message: String
) : KError(code, message) {

    /**
     * Download Task Not Found.
     *
     * @param downloadId The download ID.
     */
    public data class DownloadTaskNotFound(
        val downloadId: String
    ) : ResumeDownloadErrors(
        code = "download_task_not_found",
        message = "Download Task Not Found. Download ID: $downloadId"
    )

    /**
     * Download Is Not Paused.
     *
     * @param downloadId The download ID.
     */
    public data class DownloadIsNotPaused(
        val downloadId: String
    ) : ResumeDownloadErrors(
        code = "download_is_not_paused",
        message = "Download Is Not Paused. Download ID: $downloadId"
    )

    /**
     * Download Already Resumed.
     *
     * @param downloadId The download ID.
     */
    public data class DownloadAlreadyResumed(
        val downloadId: String
    ) : ResumeDownloadErrors(
        code = "download_already_resumed",
        message = "Download Already Resumed. Download ID: $downloadId"
    )

    /**
     * Resume Download Failed.
     *
     * @param cause The cause.
     */
    public data class ResumeDownloadFailed(
        override val cause: String
    ) : ResumeDownloadErrors(
        code = "pause_download_failed",
        message = "Pause Download Failed."
    )
}