package io.github.giovanniandreuzza.nimbus.core.application.dtos

import io.github.giovanniandreuzza.explicitarchitecture.core.application.dtos.IsDto

/**
 * Get All Downloads Response.
 *
 * @param downloads The downloads.
 * @author Giovanni Andreuzza
 */
@IsDto
public data class GetAllDownloadsResponse(val downloads: Map<String, DownloadTaskDTO>)