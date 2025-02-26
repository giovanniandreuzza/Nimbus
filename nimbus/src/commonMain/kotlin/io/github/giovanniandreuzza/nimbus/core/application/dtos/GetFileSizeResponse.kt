package io.github.giovanniandreuzza.nimbus.core.application.dtos

import io.github.giovanniandreuzza.explicitarchitecture.core.application.dtos.Dto
import io.github.giovanniandreuzza.explicitarchitecture.core.application.dtos.IsDto

/**
 * Get file size response.
 *
 * @param fileSize The file size.
 * @author Giovanni Andreuzza
 */
@IsDto
public data class GetFileSizeResponse(
    val fileSize: Long
) : Dto