package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.errors.IsFrameworkError
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Error when querying free space on the volume that contains a path.
 *
 * @author Giovanni Andreuzza
 */
@IsFrameworkError
public sealed class GetUsableSpaceError(
    override val code: String,
    override val message: String,
    override val cause: KError? = null
) : KError(code, message, cause) {

    @IsFrameworkError
    public data object Unsupported : GetUsableSpaceError(
        code = "usable_space_unsupported",
        message = "Usable space is not available on this platform or path."
    )

    @IsFrameworkError
    public data class IoFailed(
        override val cause: KError
    ) : GetUsableSpaceError(
        code = "usable_space_io_failed",
        message = "Could not query usable disk space.",
        cause = cause
    )
}
