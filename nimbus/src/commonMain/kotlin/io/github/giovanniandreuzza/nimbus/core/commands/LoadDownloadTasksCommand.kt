package io.github.giovanniandreuzza.nimbus.core.commands

import io.github.giovanniandreuzza.explicitarchitecture.core.application.commands.IsCommand
import io.github.giovanniandreuzza.explicitarchitecture.core.application.dtos.Empty
import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.UseCase
import io.github.giovanniandreuzza.nimbus.core.application.errors.FailedToLoadDownloadTasks

/**
 * Load download tasks command.
 *
 * @author Giovanni Andreuzza
 */
@IsCommand
internal interface LoadDownloadTasksCommand : UseCase<Empty, Empty, FailedToLoadDownloadTasks>