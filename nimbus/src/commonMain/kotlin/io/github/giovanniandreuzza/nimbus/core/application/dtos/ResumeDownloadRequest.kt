package io.github.giovanniandreuzza.nimbus.core.application.dtos

import io.github.giovanniandreuzza.explicitarchitecture.application.Application

/**
 * Resume download request.
 *
 * @param downloadId The download id.
 * @author Giovanni Andreuzza
 */
public data class ResumeDownloadRequest(
    val downloadId: String
) : Application