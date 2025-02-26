package io.github.giovanniandreuzza.nimbus.frameworks.downloadmanager.errors

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.errors.IsFrameworkError
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Get File Size Failed.
 *
 * @param cause cause.
 * @author Giovanni Andreuzza
 */
@IsFrameworkError
public data class GetFileSizeFailed(override val cause: String) : KError(
    code = "get_file_size_failed",
    message = "Get File Size Failed."
)