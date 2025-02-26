package io.github.giovanniandreuzza.nimbus.core.commands

import io.github.giovanniandreuzza.explicitarchitecture.core.application.commands.IsCommand
import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.UseCase
import io.github.giovanniandreuzza.nimbus.core.application.dtos.PauseDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.PauseDownloadResponse
import io.github.giovanniandreuzza.nimbus.core.domain.errors.PauseDownloadErrors

/**
 * Pause download command.
 *
 * @author Giovanni Andreuzza
 */
@IsCommand
internal interface PauseDownloadCommand :
    UseCase<PauseDownloadRequest, PauseDownloadResponse, PauseDownloadErrors>