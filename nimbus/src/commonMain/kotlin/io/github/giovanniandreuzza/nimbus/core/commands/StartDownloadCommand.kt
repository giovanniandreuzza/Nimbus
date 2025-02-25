package io.github.giovanniandreuzza.nimbus.core.commands

import io.github.giovanniandreuzza.explicitarchitecture.application.UseCase
import io.github.giovanniandreuzza.nimbus.core.application.dtos.StartDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.StartDownloadResponse
import io.github.giovanniandreuzza.nimbus.core.domain.errors.StartDownloadErrors

/**
 * Start download command.
 *
 * @author Giovanni Andreuzza
 */
internal interface StartDownloadCommand :
    UseCase<StartDownloadRequest, StartDownloadResponse, StartDownloadErrors>