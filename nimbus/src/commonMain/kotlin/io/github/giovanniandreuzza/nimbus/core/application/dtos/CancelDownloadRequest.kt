package io.github.giovanniandreuzza.nimbus.core.application.dtos

import io.github.giovanniandreuzza.explicitarchitecture.application.Application

/**
 * Cancel Download Request.
 *
 * @param downloadId The download id.
 * @author Giovanni Andreuzza
 */
public data class CancelDownloadRequest(
    val downloadId: String
) : Application