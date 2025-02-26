package io.github.giovanniandreuzza.nimbus.core.application.dtos

import io.github.giovanniandreuzza.explicitarchitecture.core.application.dtos.Dto
import io.github.giovanniandreuzza.explicitarchitecture.core.application.dtos.IsDto

/**
 * Download Request.
 *
 * @param fileUrl URL to download.
 * @param filePath Path to save the file.
 * @param fileName File name.
 * @author Giovanni Andreuzza
 */
@IsDto
public data class DownloadRequest(
    val fileUrl: String,
    val filePath: String,
    val fileName: String
) : Dto
