package io.github.giovanniandreuzza.nimbus.core.application

import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.presentation.NimbusError
import io.github.giovanniandreuzza.nimbus.presentation.PermanentNimbusErrorCause
import io.github.giovanniandreuzza.nimbus.presentation.TemporaryNimbusErrorCause

internal fun GetFileSizeError.toNimbusError(): NimbusError = when (this) {
    is GetFileSizeError.TemporaryError -> NimbusError.TemporaryError(
        TemporaryNimbusErrorCause.GetFileSizeFailed(
            errorCause
        )
    )

    is GetFileSizeError.PermanentError -> NimbusError.PermanentError(
        PermanentNimbusErrorCause.GetFileSizeFailed(
            errorCause
        )
    )
}

internal fun DownloadError.toNimbusError(): NimbusError = when (this) {
    is DownloadError.TemporaryError -> NimbusError.TemporaryError(
        TemporaryNimbusErrorCause.DownloadFailed(
            errorCause
        )
    )

    is DownloadError.PermanentError -> NimbusError.PermanentError(
        PermanentNimbusErrorCause.DownloadFailed(
            errorCause
        )
    )
}
