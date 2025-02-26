package io.github.giovanniandreuzza.nimbus.core.queries

import io.github.giovanniandreuzza.explicitarchitecture.core.application.queries.IsQuery
import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.UseCase
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadTaskNotFound

/**
 * Get Download Task Query.
 *
 * @author Giovanni Andreuzza
 */
@IsQuery
internal interface GetDownloadTaskQuery :
    UseCase<DownloadRequest, DownloadTaskDTO, DownloadTaskNotFound>