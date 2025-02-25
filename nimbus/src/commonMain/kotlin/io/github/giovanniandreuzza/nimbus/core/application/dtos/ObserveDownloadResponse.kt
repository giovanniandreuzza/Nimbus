package io.github.giovanniandreuzza.nimbus.core.application.dtos

import io.github.giovanniandreuzza.explicitarchitecture.application.Application
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import kotlinx.coroutines.flow.Flow

/**
 * Observe download response.
 *
 * @param downloadFlow the download flow.
 * @author Giovanni Andreuzza
 */
public data class ObserveDownloadResponse(
    val downloadFlow: Flow<DownloadState>
) : Application