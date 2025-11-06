package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.errors.IsFrameworkError
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Get File Size Error.
 *
 * @param code Error code.
 * @param message Error message.
 * @param cause Cause.
 * @author Giovanni Andreuzza
 */
@IsFrameworkError
public sealed class GetFileSizeError(
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
     *
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    public data object FileNotFound : GetFileSizeError(
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
    public data class ReadPermissionDenied(override val cause: KError) : GetFileSizeError(
        code = "read_permission_denied",
        message = "Read permission denied.",
        cause = cause
    )

}