package io.github.giovanniandreuzza.nimbus.presentation

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryGetFileSizeErrorCause

/**
 * Typed cause for [NimbusError.TemporaryError].
 *
 * Every variant represents a transient condition that may resolve on retry.
 * Callers can exhaustively `when`-match to decide how to surface each case.
 *
 * @author Giovanni Andreuzza
 */
public sealed class TemporaryNimbusErrorCause(
    override val code: String,
    override val message: String,
    override val cause: KError? = null
) : KError(code, message, cause) {
    /**
     * A download attempt failed with a transient error.
     *
     * @param errorCause The typed download-level cause — exhaustively matchable via `when`.
     */
    public data class DownloadFailed(
        val errorCause: TemporaryDownloadErrorCause
    ) : TemporaryNimbusErrorCause(
        code = "download_failed",
        message = "A temporary download error occurred.",
        cause = errorCause
    )

    /**
     * Retrieving the remote file size failed with a transient error.
     *
     * @param errorCause The typed file-size-level cause — exhaustively matchable via `when`.
     */
    public data class GetFileSizeFailed(
        val errorCause: TemporaryGetFileSizeErrorCause
    ) : TemporaryNimbusErrorCause(
        code = "get_file_size_failed",
        message = "A temporary error occurred while retrieving the file size.",
        cause = errorCause
    )
}
