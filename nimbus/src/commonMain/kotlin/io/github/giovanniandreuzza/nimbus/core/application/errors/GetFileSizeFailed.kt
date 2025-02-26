package io.github.giovanniandreuzza.nimbus.core.application.errors

import io.github.giovanniandreuzza.explicitarchitecture.core.application.errors.IsApplicationError
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Get File Size Failed.
 *
 * @param error the error reason.
 * @author Giovanni Andreuzza
 */
@IsApplicationError
public data class GetFileSizeFailed(val error: String) : KError(
    code = "get_file_size_failed",
    message = "Get File Size Failed.",
    cause = error
)