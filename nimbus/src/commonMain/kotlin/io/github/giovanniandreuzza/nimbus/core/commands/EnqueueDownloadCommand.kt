package io.github.giovanniandreuzza.nimbus.core.commands

import io.github.giovanniandreuzza.explicitarchitecture.core.application.commands.IsCommand
import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.UseCase
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.domain.errors.EnqueueDownloadErrors

/**
 * Enqueue Download Command.
 *
 * @author Giovanni Andreuzza
 */
@IsCommand
internal interface EnqueueDownloadCommand :
    UseCase<DownloadRequest, DownloadTaskDTO, EnqueueDownloadErrors>