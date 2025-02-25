package io.github.giovanniandreuzza.nimbus.core.application.dtos

import io.github.giovanniandreuzza.explicitarchitecture.application.Application

/**
 * Start download request.
 *
 * @param downloadId The download id.
 * @author Giovanni Andreuzza
 */
public data class StartDownloadRequest(
    val downloadId: String
) : Application