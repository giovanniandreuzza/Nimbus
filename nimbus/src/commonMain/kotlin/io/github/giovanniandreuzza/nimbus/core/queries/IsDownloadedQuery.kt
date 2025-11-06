package io.github.giovanniandreuzza.nimbus.core.queries

import io.github.giovanniandreuzza.explicitarchitecture.core.application.queries.IsQuery
import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.UseCase
import io.github.giovanniandreuzza.nimbus.core.application.dtos.IsDownloadedRequest

/**
 * Is Downloaded Query.
 *
 * @author Giovanni Andreuzza
 */
@IsQuery
internal interface IsDownloadedQuery : UseCase<IsDownloadedRequest, Boolean>