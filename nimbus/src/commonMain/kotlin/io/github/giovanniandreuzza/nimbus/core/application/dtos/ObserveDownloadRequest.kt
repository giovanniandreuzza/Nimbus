package io.github.giovanniandreuzza.nimbus.core.application.dtos

import io.github.giovanniandreuzza.explicitarchitecture.application.Application

/**
 * Observe Download Request.
 *
 * @param downloadId the download id
 * @author Giovanni Andreuzza
 */
public data class ObserveDownloadRequest(
    val downloadId: String
) : Application