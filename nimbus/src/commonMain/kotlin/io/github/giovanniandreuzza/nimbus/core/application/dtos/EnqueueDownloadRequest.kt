package io.github.giovanniandreuzza.nimbus.core.application.dtos

import io.github.giovanniandreuzza.explicitarchitecture.core.application.dtos.IsDto

/**
 * Enqueue Download Request DTO.
 *
 * @param fileUrl The file URL.
 * @param filePath The file path.
 * @param fileName The file name.
 * @author Giovanni Andreuzza
 */
@IsDto
public data class EnqueueDownloadRequest(
    val fileUrl: String,
    val filePath: String,
    val fileName: String
)