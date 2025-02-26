package io.github.giovanniandreuzza.nimbus.frameworks.filemanager.errors

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.errors.IsFrameworkError
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * File Not Found.
 *
 * @param cause cause.
 * @author Giovanni Andreuzza
 */
@IsFrameworkError
public data class FileNotFound(override val cause: String) : KError(
    code = "file_not_found",
    message = "File Not Found."
)