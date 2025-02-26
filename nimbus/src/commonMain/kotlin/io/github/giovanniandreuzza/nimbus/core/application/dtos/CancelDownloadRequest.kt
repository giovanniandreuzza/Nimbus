package io.github.giovanniandreuzza.nimbus.core.application.dtos

import io.github.giovanniandreuzza.explicitarchitecture.core.application.dtos.Dto
import io.github.giovanniandreuzza.explicitarchitecture.core.application.dtos.IsDto

/**
 * Cancel Download Request.
 *
 * @param downloadId The download id.
 * @author Giovanni Andreuzza
 */
@IsDto
public data class CancelDownloadRequest(
    val downloadId: String
) : Dto