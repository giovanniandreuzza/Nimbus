package io.github.giovanniandreuzza.nimbus.frameworks.filemanager.errors

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.errors.IsFrameworkError
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * File Creation Failed.
 *
 * @param cause Cause.
 * @author Giovanni Andreuzza
 */
@IsFrameworkError
public data class FileCreationFailed(override val cause: String) : KError(
    code = "file_creation_failed",
    message = "File Creation Failed."
)