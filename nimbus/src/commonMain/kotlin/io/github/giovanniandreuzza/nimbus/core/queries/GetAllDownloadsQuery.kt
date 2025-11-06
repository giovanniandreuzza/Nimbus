package io.github.giovanniandreuzza.nimbus.core.queries

import io.github.giovanniandreuzza.explicitarchitecture.core.application.queries.IsQuery
import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.UseCase
import io.github.giovanniandreuzza.nimbus.core.application.dtos.GetAllDownloadsResponse

/**
 * Get All Downloads Query.
 *
 * @author Giovanni Andreuzza
 */
@IsQuery
internal interface GetAllDownloadsQuery : UseCase<Unit, GetAllDownloadsResponse>

