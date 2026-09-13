package io.github.giovanniandreuzza.nimbus.di

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onFailure
import io.github.giovanniandreuzza.nimbus.presentation.NimbusError
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogEvent
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogger
import io.github.giovanniandreuzza.nimbus.presentation.RetryPolicy
import io.github.giovanniandreuzza.nimbus.shared.utils.allowsAttempt
import io.github.giovanniandreuzza.nimbus.shared.utils.delayForAttempt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * The second of the two retry loops: what happens after a download has already failed.
 *
 * `DownloadAdapter` retries inside one download and keeps the task `Downloading`. When its
 * budget is spent the task becomes `Failed`, and with `autoStart` enabled this brings it back:
 * re-fetch the remote size, reset the task, start it again. It exists so an unattended device
 * eventually gets its file even when the server was down for an hour.
 *
 * It used to do that immediately, with no wait, no counter and no ceiling — so a backend in
 * maintenance became a request flood on a metered link, every failure costing a HEAD and a GET,
 * and a whole fleet retrying in step because nothing spread them out. The waiting, the
 * counting and the spreading all live here now; [RetryPolicy] says how much of each.
 *
 * The count is per url and in memory. It is reset when a download for that url finishes, so a
 * device that has been up for months meets its next transient failure with a two-second wait
 * rather than the five-minute one it last needed; and a restart starting from zero is correct
 * rather than merely convenient, since whatever the process knew about why it was failing is
 * gone too.
 *
 * @param retryFailedDownload `DownloadService.retryFailedDownload`.
 * @param startDownload `DownloadService.startDownload`.
 * @author Giovanni Andreuzza
 */
internal class AutoRetryScheduler(
    private val scope: CoroutineScope,
    private val policy: RetryPolicy,
    private val logger: NimbusLogger?,
    private val random: Random = Random.Default,
    private val retryFailedDownload: suspend (fileUrl: String) -> KResult<Unit, NimbusError>,
    private val startDownload: suspend (fileUrl: String) -> KResult<Unit, NimbusError>
) {
    private val mutex = Mutex()
    private val attempts = mutableMapOf<String, Int>()

    /**
     * Queues a retry for [fileUrl], whatever kind of failure it was.
     *
     * Including a permanent one, which looks wrong and is not. Several permanent causes
     * describe the *local* state rather than the origin — `LocalFileOversized`,
     * `BodyLongerThanDeclared`, `InconsistentRangeResponse` — and the reset this loop performs
     * is exactly what clears them: the partial is discarded and the file fetched again. A
     * cause that really is permanent costs one round-trip and stops there, because
     * `retryFailedDownload` re-asks the origin, gets the same answer, and the chain ends
     * without rescheduling.
     *
     * Returns at once, and deliberately: it is called from inside the failing download's own
     * `onDownloadFailed`, which still has to return so the download coroutine can release its
     * concurrency permit and deregister its job. Doing the work here would mean a retry racing
     * the job it is replacing.
     */
    fun schedule(fileUrl: String) {
        scope.launch { retryAfterBackoff(fileUrl) }
    }

    /** Forgets what [fileUrl] was counting, because it succeeded. */
    suspend fun forget(fileUrl: String) {
        mutex.withLock { attempts.remove(fileUrl) }
    }

    /**
     * A loop rather than a call that repeats itself: an unbounded policy against a backend
     * that never answers would otherwise stack a suspended frame per attempt, for days.
     */
    private suspend fun retryAfterBackoff(fileUrl: String) {
        while (true) {
            val attempt = mutex.withLock {
                val next = (attempts[fileUrl] ?: 0) + 1
                attempts[fileUrl] = next
                next
            }

            if (!policy.allowsAttempt(attempt)) {
                logger?.log(NimbusLogEvent.AutoRetryExhausted(fileUrl, attempt - 1))
                return
            }

            val wait = policy.delayForAttempt(attempt, random)
            logger?.log(NimbusLogEvent.AutoRetryScheduled(fileUrl, wait, attempt))
            delay(wait)

            val prepared = retryFailedDownload(fileUrl)
            if (prepared is Failure) {
                logger?.log(NimbusLogEvent.AutoRetryFailed(fileUrl, prepared.error))

                // Bringing the task back is itself a round-trip — it re-asks the origin for
                // the size — so it fails for the same transient reasons a download does.
                // Stopping there left a task `Failed` for good because one HEAD timed out,
                // which is the opposite of what an unbounded policy promises. A permanent
                // failure does stop it: asking again gets the same answer.
                if (prepared.error.isRetryable) continue else return
            }

            startDownload(fileUrl).onFailure {
                logger?.log(NimbusLogEvent.AutoStartFailed(fileUrl, it))
            }
            return
        }
    }
}
