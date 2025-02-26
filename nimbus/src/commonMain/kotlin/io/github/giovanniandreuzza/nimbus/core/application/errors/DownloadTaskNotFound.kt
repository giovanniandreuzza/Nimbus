package io.github.giovanniandreuzza.nimbus.core.application.errors

import io.github.giovanniandreuzza.explicitarchitecture.core.application.errors.IsApplicationError
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Download Task Not Found.
 *
 * @param error the error reason
 * @author Giovanni Andreuzza
 */
@IsApplicationError
public data class DownloadTaskNotFound(
    val error: String? = null
) : KError(
    code = "download_task_not_found",
    message = "Download Task Not Found.",
    cause = error
)