package io.github.giovanniandreuzza.nimbus.core.application.dtos

import io.github.giovanniandreuzza.explicitarchitecture.core.application.dtos.IsDto

/**
 * Resume download request.
 *
 * @param fileUrl the file url
 * @author Giovanni Andreuzza
 */
@IsDto
public data class ResumeDownloadRequest(val fileUrl: String)