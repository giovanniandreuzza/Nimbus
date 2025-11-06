package io.github.giovanniandreuzza.nimbus.core.application.errors

import io.github.giovanniandreuzza.explicitarchitecture.core.application.errors.IsApplicationError
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Failed To Load Download Tasks.
 *
 * @param error the error reason
 * @author Giovanni Andreuzza
 */
@IsApplicationError
public data class FailedToLoadDownloadTasks(val error: KError) : KError(
    code = "failed_to_load_download_tasks",
    message = "Failed to load download tasks",
    cause = error
)