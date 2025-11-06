package io.github.giovanniandreuzza.nimbus.core.queries

import io.github.giovanniandreuzza.explicitarchitecture.core.application.queries.IsQuery
import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.UseCase
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ObserveDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ObserveDownloadResponse
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadTaskNotFound

/**
 * Observe Download Query.
 *
 * @author Giovanni Andreuzza
 */
@IsQuery
internal interface ObserveDownloadQuery :
    UseCase<ObserveDownloadRequest, KResult<ObserveDownloadResponse, DownloadTaskNotFound>>