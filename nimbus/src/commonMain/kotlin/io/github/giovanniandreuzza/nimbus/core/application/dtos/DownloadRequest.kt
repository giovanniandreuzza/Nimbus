package io.github.giovanniandreuzza.nimbus.core.application.dtos

import io.github.giovanniandreuzza.explicitarchitecture.application.Application

/**
 * Download Request.
 *
 * @param fileUrl URL to download.
 * @param filePath Path to save the file.
 * @param fileName File name.
 * @author Giovanni Andreuzza
 */
public data class DownloadRequest(
    val fileUrl: String,
    val filePath: String,
    val fileName: String
) : Application
