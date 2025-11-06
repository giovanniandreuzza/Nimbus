package io.github.giovanniandreuzza.nimbus.frameworks.store.errors

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.errors.IsFrameworkError
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Does Store Exist Error.
 *
 * @param code Error code.
 * @param message Error message.
 * @param cause Cause.
 * @author Giovanni Andreuzza
 */
@IsFrameworkError
internal sealed class DoesStoreExistError(
    override val code: String,
    override val message: String,
    override val cause: KError
) : KError(
    code = code,
    message = message,
    cause = cause
) {

    /**
     * Read Permission Denied Error.
     *
     * @param cause Cause.
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    data class ReadPermissionDenied(override val cause: KError) : DoesStoreExistError(
        code = "read_permission_denied",
        message = "Read permission denied.",
        cause = cause
    )
}