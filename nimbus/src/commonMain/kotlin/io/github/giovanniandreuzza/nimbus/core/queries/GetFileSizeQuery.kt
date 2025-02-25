package io.github.giovanniandreuzza.nimbus.core.queries

import io.github.giovanniandreuzza.explicitarchitecture.application.UseCase
import io.github.giovanniandreuzza.nimbus.core.application.dtos.GetFileSizeRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.GetFileSizeResponse
import io.github.giovanniandreuzza.nimbus.core.errors.GetFileSizeFailed

/**
 * Get File Size Query.
 *
 * @author Giovanni Andreuzza
 */
internal interface GetFileSizeQuery :
    UseCase<GetFileSizeRequest, GetFileSizeResponse, GetFileSizeFailed>