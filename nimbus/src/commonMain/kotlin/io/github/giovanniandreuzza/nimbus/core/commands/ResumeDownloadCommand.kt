package io.github.giovanniandreuzza.nimbus.core.commands

import io.github.giovanniandreuzza.explicitarchitecture.application.UseCase
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ResumeDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ResumeDownloadResponse
import io.github.giovanniandreuzza.nimbus.core.domain.errors.ResumeDownloadErrors

/**
 * Resume download command.
 *
 * @author Giovanni Andreuzza
 */
internal interface ResumeDownloadCommand :
    UseCase<ResumeDownloadRequest, ResumeDownloadResponse, ResumeDownloadErrors>