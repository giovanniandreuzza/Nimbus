package io.github.giovanniandreuzza.nimbus.core.queries

import io.github.giovanniandreuzza.explicitarchitecture.core.application.queries.IsQuery
import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.UseCase
import io.github.giovanniandreuzza.nimbus.core.application.dtos.GetFileSizeRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.GetFileSizeResponse
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeFailed

/**
 * Get File Size Query.
 *
 * @author Giovanni Andreuzza
 */
@IsQuery
internal interface GetFileSizeQuery :
    UseCase<GetFileSizeRequest, GetFileSizeResponse, GetFileSizeFailed>