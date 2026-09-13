package io.github.giovanniandreuzza.nimbus.presentation

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Structured observability events for [NimbusLogger].
 *
 * @author Giovanni Andreuzza
 */
public sealed class NimbusLogEvent {

    /** A new task was persisted (metadata only until bytes flow). */
    public data class DownloadEnqueued(
        public val fileUrl: String,
        public val filePath: String,
        public val expectedSizeBytes: Long
    ) : NimbusLogEvent()

    /** Start/resume was requested for an existing task. */
    public data class DownloadStartRequested(
        public val fileUrl: String
    ) : NimbusLogEvent()

    public data class DownloadFinished(
        public val fileUrl: String
    ) : NimbusLogEvent()

    public data class DownloadFailed(
        public val fileUrl: String,
        public val error: NimbusError
    ) : NimbusLogEvent()

    /**
     * [ensureDownloaded] found the file already complete (metadata + size on disk OK).
     */
    public data class EnsureDownloadedAlreadyComplete(
        public val fileUrl: String
    ) : NimbusLogEvent()

    /**
     * [ensureDownloaded] removed a stale Finished task (e.g. missing file) before re-downloading.
     */
    public data class EnsureDownloadedStaleFinishedRemoved(
        public val fileUrl: String
    ) : NimbusLogEvent()

    public data class InsufficientDiskSpace(
        public val fileUrl: String?,
        public val path: String,
        public val requiredBytes: Long,
        public val availableBytes: Long
    ) : NimbusLogEvent()

    /**
     * [autoStart] mode failed to start a newly enqueued download in the background.
     * The task remains in [io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState.Enqueued]
     * state and can be started manually via [NimbusAPI.startDownload].
     */
    public data class AutoStartFailed(
        public val fileUrl: String,
        public val error: NimbusError
    ) : NimbusLogEvent()

    /**
     * [autoStart] mode failed to retry a failed download in the background.
     * The task remains in [io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState.Failed]
     * state and can be retried manually via [NimbusAPI.retryFailedDownload].
     */
    public data class AutoRetryFailed(
        public val fileUrl: String,
        public val error: NimbusError
    ) : NimbusLogEvent()

    /**
     * The library is going to retry a failed download, and when.
     *
     * The wait is what this event is really reporting. Until it existed the retry was
     * immediate, so a backend in maintenance turned one failed task into a request flood and
     * a log full of [AutoRetryFailed] with nothing to explain the rate.
     *
     * @param attempt which retry this is for this url, counting from 1. It resets when a
     * download for the url finishes.
     */
    public data class AutoRetryScheduled(
        public val fileUrl: String,
        public val delayMs: Long,
        public val attempt: Int
    ) : NimbusLogEvent()

    /**
     * The auto-retry budget for this url is spent and nothing more will be attempted.
     *
     * Only reachable when a caller set [io.github.giovanniandreuzza.nimbus.presentation.RetryPolicy.maxAttempts]
     * on the auto-retry policy; the default keeps trying, because on an unattended device
     * giving up permanently is what a technician's visit looks like.
     */
    public data class AutoRetryExhausted(
        public val fileUrl: String,
        public val attempts: Int
    ) : NimbusLogEvent()

    /**
     * Something threw that this library did not anticipate, with the throwable itself.
     *
     * Every other event carries a `KError`, which is a code and a message — enough to act on,
     * and not enough to diagnose: an `UnexpectedError` reaches a monitoring backend as "null"
     * or "Index 3 out of bounds" with nothing saying where. On a device that cannot be
     * attached to a debugger, that is the difference between a bug report and a shrug.
     *
     * The throwable is handed over rather than formatted here, so a logger can take the stack,
     * the type, or nothing at all. It is the one event that carries a platform type, which is
     * why it exists alongside [DownloadFailed] rather than replacing anything in it.
     *
     * @param fileUrl the download it happened under.
     * @param throwable what was thrown.
     */
    public data class Unexpected(
        public val fileUrl: String,
        public val throwable: Throwable
    ) : NimbusLogEvent()

    /**
     * A task state-change could not be persisted to disk. The in-memory state was updated
     * successfully; the disk store may be out of sync until the next successful write.
     */
    public data class PersistenceFailed(
        public val fileUrl: String,
        public val cause: KError
    ) : NimbusLogEvent()

    /**
     * A coalesced background commit of the store failed. Every state the caller was told
     * was durable already is; what may be missing from disk are the states the boot path
     * can re-derive — an enqueue, a progress position, a pause — so a device that restarts
     * now recovers, it just may not remember the most recent non-terminal change.
     */
    public data class StoreFlushFailed(
        public val cause: KError
    ) : NimbusLogEvent()

    /**
     * The persisted store was unusable — it could not be decoded, or it carried a
     * schema version this build does not understand — and was discarded. Every
     * pending task is gone: callers have to enqueue again.
     */
    public data class StoreReset(
        public val reason: String
    ) : NimbusLogEvent()
}

/**
 * Optional structured logging for kiosk / 24×7 and production diagnostics.
 *
 * Implementations should be fast and non-throwing; use a background dispatcher if needed.
 */
public fun interface NimbusLogger {
    public suspend fun log(event: NimbusLogEvent)
}
