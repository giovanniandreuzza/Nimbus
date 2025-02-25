package io.github.giovanniandreuzza.nimbus.core.commands

import io.github.giovanniandreuzza.explicitarchitecture.application.UseCase
import io.github.giovanniandreuzza.nimbus.core.application.dtos.PauseDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.PauseDownloadResponse
import io.github.giovanniandreuzza.nimbus.core.domain.errors.PauseDownloadErrors

/**
 * Pause download command.
 *
 * @author Giovanni Andreuzza
 */
internal interface PauseDownloadCommand :
    UseCase<PauseDownloadRequest, PauseDownloadResponse, PauseDownloadErrors>