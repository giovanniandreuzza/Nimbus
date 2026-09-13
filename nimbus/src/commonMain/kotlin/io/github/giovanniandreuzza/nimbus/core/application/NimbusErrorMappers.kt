package io.github.giovanniandreuzza.nimbus.core.application

import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.core.application.errors.TransitionFailure
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

/**
 * A refused or unpersisted transition, as the caller of [NimbusAPI] sees it.
 *
 * The three outcomes map onto three causes the caller can act on: there is no such task, the
 * task is in a state that does not allow what was asked, or the change is in memory and the
 * disk did not take it.
 */
internal fun TransitionFailure.toNimbusError(): NimbusError = when (this) {
    TransitionFailure.NotFound -> NimbusError.PermanentError(
        PermanentNimbusErrorCause.DownloadNotFound
    )

    is TransitionFailure.Refused -> NimbusError.PermanentError(
        PermanentNimbusErrorCause.InvalidState(currentState)
    )

    is TransitionFailure.NotPersisted -> NimbusError.PermanentError(
        PermanentNimbusErrorCause.StorageError(cause)
    )
}
