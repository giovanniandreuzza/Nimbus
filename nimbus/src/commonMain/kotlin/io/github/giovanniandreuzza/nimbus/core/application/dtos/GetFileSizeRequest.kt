package io.github.giovanniandreuzza.nimbus.core.application.dtos

import io.github.giovanniandreuzza.explicitarchitecture.core.application.dtos.Dto
import io.github.giovanniandreuzza.explicitarchitecture.core.application.dtos.IsDto

/**
 * Get file size request.
 *
 * @param fileUrl The file url.
 * @author Giovanni Andreuzza
 */
@IsDto
public data class GetFileSizeRequest(
    val fileUrl: String
) : Dto