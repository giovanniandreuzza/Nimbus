package io.github.giovanniandreuzza.nimbus.core.queries

import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO

/**
 * Get All Downloads Query.
 *
 * @author Giovanni Andreuzza
 */
internal interface GetAllDownloadsQuery {

    suspend fun execute(): Map<String, DownloadTaskDTO>

}