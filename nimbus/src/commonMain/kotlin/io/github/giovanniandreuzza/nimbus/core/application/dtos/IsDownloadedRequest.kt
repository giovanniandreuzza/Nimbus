package io.github.giovanniandreuzza.nimbus.core.application.dtos

import io.github.giovanniandreuzza.explicitarchitecture.core.application.dtos.IsDto

/**
 * Is Downloaded Request.
 *
 * @param fileUrl the file url.
 * @author Giovanni Andreuzza
 */
@IsDto
public data class IsDownloadedRequest(val fileUrl: String)