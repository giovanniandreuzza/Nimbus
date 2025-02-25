package io.github.giovanniandreuzza.nimbus.core.errors

import io.github.giovanniandreuzza.explicitarchitecture.shared.KError

/**
 * Download Task Not Found.
 *
 * @param downloadId download id.
 * @author Giovanni Andreuzza
 */
public data class DownloadTaskNotFound(val downloadId: String) : KError(
    code = "download_task_not_found",
    message = "Download Task Not Found. Download ID: $downloadId"
)

public data class GetFileSizeFailed(override val cause: String) : KError(
    code = "get_file_size_failed",
    message = "Get File Size Failed."
)

public data class FileCreationFailed(override val cause: String) : KError(
    code = "file_creation_failed",
    message = "File Creation Failed."
)

public data class FileNotFound(override val cause: String) : KError(
    code = "file_not_found",
    message = "File Not Found."
)