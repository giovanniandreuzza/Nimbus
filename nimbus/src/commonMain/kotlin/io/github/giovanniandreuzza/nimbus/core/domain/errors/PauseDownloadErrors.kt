package io.github.giovanniandreuzza.nimbus.core.domain.errors

import io.github.giovanniandreuzza.explicitarchitecture.core.domain.errors.IsDomainError
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Pause Download Errors.
 *
 * @param code Error code.
 * @param message Error message.
 * @param cause Error cause.
 * @author Giovanni Andreuzza
 */
@IsDomainError
public sealed class PauseDownloadErrors(
    override val code: String,
    override val message: String,
    override val cause: KError? = null
) : KError(code, message) {

    /**
     * Download Task Not Found.
     *
     * @param downloadId The download ID.
     */
    @IsDomainError
    public data class DownloadTaskNotFound(
        val downloadId: String
    ) : PauseDownloadErrors(
        code = "download_task_not_found",
        message = "Download Task Not Found. Download ID: $downloadId"
    )

    /**
     * Download Is Not Downloading.
     *
     * @param downloadId The download ID.
     */
    @IsDomainError
    public data class DownloadIsNotDownloading(
        val downloadId: String
    ) : PauseDownloadErrors(
        code = "download_is_not_downloading",
        message = "Download Is Not Downloading. Download ID: $downloadId"
    )

    /**
     * Download Already Paused.
     *
     * @param downloadId The download ID.
     */
    @IsDomainError
    public data class DownloadAlreadyPaused(
        val downloadId: String
    ) : PauseDownloadErrors(
        code = "download_already_paused",
        message = "Download Already Paused. Download ID: $downloadId"
    )

    /**
     * Pause Download Failed.
     *
     * @param cause The cause.
     */
    @IsDomainError
    public data class PauseDownloadFailed(
        override val cause: KError
    ) : PauseDownloadErrors(
        code = "pause_download_failed",
        message = "Pause Download Failed.",
        cause = cause
    )
}