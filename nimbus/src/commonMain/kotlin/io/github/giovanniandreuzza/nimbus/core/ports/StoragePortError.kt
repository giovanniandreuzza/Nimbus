package io.github.giovanniandreuzza.nimbus.core.ports

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * A storage operation failed.
 *
 * Deliberately one case. Core does not reason about filesystems: reading every failure
 * branch in `DownloadService`, the only distinctions it ever drew were the three benign
 * outcomes that are now success values ([CreateOutcome.AlreadyExists],
 * [DeleteOutcome.NotFound], a null usable-space), and everything else went to
 * `PermanentNimbusErrorCause.StorageError` unread. Reproducing the eight plugin error
 * families in core would describe filesystem outcomes to a layer that has no use for them.
 *
 * [cause] carries the plugin's own error, so nothing is lost on the way out to the caller.
 *
 * @param cause the underlying storage failure, as reported by the plugin.
 * @author Giovanni Andreuzza
 */
internal data class StoragePortError(override val cause: KError) : KError(
    code = "storage_error",
    message = cause.message,
    cause = cause
)
