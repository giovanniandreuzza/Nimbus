package io.github.giovanniandreuzza.nimbus.core.application

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.presentation.NimbusError

internal fun GetFileSizeError.toNimbusError(): NimbusError = when (this) {
    is GetFileSizeError.ResourceNotFound -> NimbusError.ResourceNotFound
    is GetFileSizeError.TemporaryError -> NimbusError.TemporaryError(cause)
    is GetFileSizeError.PermanentError -> NimbusError.PermanentError(cause)
    is GetFileSizeError.UnexpectedError -> NimbusError.UnexpectedError(cause)
}

internal fun DownloadError.toNimbusError(): NimbusError = when (this) {
    is DownloadError.ResourceNotFound -> NimbusError.ResourceNotFound
    is DownloadError.TemporaryError -> NimbusError.TemporaryError(cause)
    is DownloadError.PermanentError -> NimbusError.PermanentError(cause)
    is DownloadError.UnexpectedError -> NimbusError.UnexpectedError(cause)
    DownloadError.RangeNotSatisfiable -> NimbusError.PermanentError(
        KError("range_not_satisfiable", message)
    )
    is DownloadError.InconsistentRangeResponse -> NimbusError.PermanentError(
        cause ?: KError("inconsistent_range_response", message)
    )
    is DownloadError.LocalFileStateUnreadable -> NimbusError.PermanentError(
        cause ?: KError("local_file_unreadable", message)
    )
    is DownloadError.LocalFileOversized -> NimbusError.PermanentError(
        cause ?: KError("local_file_oversized", message)
    )
}
