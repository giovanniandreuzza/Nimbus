package io.github.giovanniandreuzza.nimbus.core.commands

import io.github.giovanniandreuzza.explicitarchitecture.application.UseCase
import io.github.giovanniandreuzza.nimbus.core.application.dtos.CancelDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.CancelDownloadResponse
import io.github.giovanniandreuzza.nimbus.core.errors.DownloadTaskNotFound

/**
 * Cancel Download Command.
 *
 * @author Giovanni Andreuzza
 */
internal interface CancelDownloadCommand :
    UseCase<CancelDownloadRequest, CancelDownloadResponse, DownloadTaskNotFound>