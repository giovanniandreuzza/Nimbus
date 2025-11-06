package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.errors.IsFrameworkError
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Get File Sink Error.
 *
 * @param code Error code.
 * @param message Error message.
 * @param cause Cause.
 * @author Giovanni Andreuzza
 */
@IsFrameworkError
public sealed class GetFileSinkError(
    override val code: String,
    override val message: String,
    override val cause: KError? = null
) : KError(
    code = code,
    message = message,
    cause = cause
) {

    /**
     * File Not Found Error.
     */
    @IsFrameworkError
    public data object FileNotFound : GetFileSinkError(
        code = "file_not_found",
        message = "The specified file was not found."
    )

    /**
     * Read Permission Denied Error.
     *
     * @param cause Cause.
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    public data class ReadPermissionDenied(override val cause: KError) : GetFileSinkError(
        code = "read_permission_denied",
        message = "Read permission denied.",
        cause = cause
    )

    /**
     * Write Permission Denied Error.
     *
     * @param cause Cause.
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    public data class WritePermissionDenied(override val cause: KError) : GetFileSinkError(
        code = "write_permission_denied",
        message = "Write permission denied.",
        cause = cause
    )
}