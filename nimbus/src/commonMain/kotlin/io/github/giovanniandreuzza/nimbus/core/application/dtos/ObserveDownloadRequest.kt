package io.github.giovanniandreuzza.nimbus.core.application.dtos

import io.github.giovanniandreuzza.explicitarchitecture.core.application.dtos.Dto
import io.github.giovanniandreuzza.explicitarchitecture.core.application.dtos.IsDto

/**
 * Observe Download Request.
 *
 * @param downloadId the download id
 * @author Giovanni Andreuzza
 */
@IsDto
public data class ObserveDownloadRequest(
    val downloadId: String
) : Dto