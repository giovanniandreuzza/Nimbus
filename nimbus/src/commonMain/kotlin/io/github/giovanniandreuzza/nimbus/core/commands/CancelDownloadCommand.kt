package io.github.giovanniandreuzza.nimbus.core.commands

import io.github.giovanniandreuzza.explicitarchitecture.core.application.commands.IsCommand
import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.UseCase
import io.github.giovanniandreuzza.nimbus.core.application.dtos.CancelDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.CancelDownloadResponse
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadTaskNotFound

/**
 * Cancel Download Command.
 *
 * @author Giovanni Andreuzza
 */
@IsCommand
internal interface CancelDownloadCommand :
    UseCase<CancelDownloadRequest, CancelDownloadResponse, DownloadTaskNotFound>