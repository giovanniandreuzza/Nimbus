package io.github.giovanniandreuzza.nimbus.core.queries

import io.github.giovanniandreuzza.explicitarchitecture.application.UseCase
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.errors.DownloadTaskNotFound

/**
 * Get Download Task Query.
 *
 * @author Giovanni Andreuzza
 */
internal interface GetDownloadTaskQuery :
    UseCase<DownloadRequest, DownloadTaskDTO, DownloadTaskNotFound>