package io.github.giovanniandreuzza.nimbus.frameworks.store.errors

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.errors.IsFrameworkError
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Delete Store Error.
 *
 * @param code Error code.
 * @param message Error message.
 * @param cause Cause.
 * @author Giovanni Andreuzza
 */
@IsFrameworkError
internal sealed class DeleteStoreError(
    override val code: String,
    override val message: String,
    override val cause: KError? = null
) : KError(
    code = code,
    message = message,
    cause = cause
) {

    /**
     * Store Not Found Error.
     *
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    data object StoreNotFound : DeleteStoreError(
        code = "store_not_found",
        message = "Store not found."
    )

    /**
     * Store Deletion Failed Error.
     *
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    data object StoreDeletionFailed : DeleteStoreError(
        code = "store_deletion_failed",
        message = "Store deletion failed."
    )

    /**
     * I/O Error.
     *
     * @param cause Cause.
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    data class IOError(override val cause: KError) : DeleteStoreError(
        code = "io_error",
        message = "An I/O error occurred.",
        cause = cause
    )

    /**
     * Read Permission Denied Error.
     *
     * @param cause Cause.
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    data class ReadPermissionDenied(override val cause: KError) : DeleteStoreError(
        code = "read_permission_denied",
        message = "Read permission denied.",
        cause = cause
    )

    /**
     * Delete Permission Denied Error.
     *
     * @param cause Cause.
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    data class DeletePermissionDenied(override val cause: KError) : DeleteStoreError(
        code = "delete_permission_denied",
        message = "Delete permission denied.",
        cause = cause
    )
}