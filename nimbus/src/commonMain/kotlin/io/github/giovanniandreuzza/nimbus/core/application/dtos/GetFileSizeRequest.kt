package io.github.giovanniandreuzza.nimbus.core.application.dtos

import io.github.giovanniandreuzza.explicitarchitecture.application.Application

/**
 * Get file size request.
 *
 * @param fileUrl The file url.
 * @author Giovanni Andreuzza
 */
public data class GetFileSizeRequest(
    val fileUrl: String
) : Application