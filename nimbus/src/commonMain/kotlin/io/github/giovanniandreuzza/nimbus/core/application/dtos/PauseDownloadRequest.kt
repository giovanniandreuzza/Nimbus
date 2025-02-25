package io.github.giovanniandreuzza.nimbus.core.application.dtos

import io.github.giovanniandreuzza.explicitarchitecture.application.Application

/**
 * Pause download request.
 *
 * @param downloadId The download id.
 * @author Giovanni Andreuzza
 */
public data class PauseDownloadRequest(
    val downloadId: String
) : Application