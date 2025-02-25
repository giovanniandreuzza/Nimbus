package io.github.giovanniandreuzza.nimbus.core.queries

import io.github.giovanniandreuzza.explicitarchitecture.application.UseCase
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ObserveDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ObserveDownloadResponse
import io.github.giovanniandreuzza.nimbus.core.errors.DownloadTaskNotFound

internal interface ObserveDownloadQuery :
    UseCase<ObserveDownloadRequest, ObserveDownloadResponse, DownloadTaskNotFound>