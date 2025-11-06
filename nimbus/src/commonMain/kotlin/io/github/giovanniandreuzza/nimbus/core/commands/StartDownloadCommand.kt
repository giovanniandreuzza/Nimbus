package io.github.giovanniandreuzza.nimbus.core.commands

import io.github.giovanniandreuzza.explicitarchitecture.core.application.commands.IsCommand
import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.UseCase
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.core.application.dtos.StartDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.domain.errors.StartDownloadErrors

/**
 * Start download command.
 *
 * @author Giovanni Andreuzza
 */
@IsCommand
internal interface StartDownloadCommand :
    UseCase<StartDownloadRequest, KResult<Unit, StartDownloadErrors>>