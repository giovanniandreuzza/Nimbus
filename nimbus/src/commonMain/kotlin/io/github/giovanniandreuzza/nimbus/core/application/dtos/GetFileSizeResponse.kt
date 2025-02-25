package io.github.giovanniandreuzza.nimbus.core.application.dtos

import io.github.giovanniandreuzza.explicitarchitecture.application.Application

/**
 * Get file size response.
 *
 * @param fileSize The file size.
 * @author Giovanni Andreuzza
 */
public data class GetFileSizeResponse(
    val fileSize: Long
) : Application