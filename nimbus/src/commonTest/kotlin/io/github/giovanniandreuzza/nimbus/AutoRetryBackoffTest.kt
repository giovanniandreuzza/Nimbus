package io.github.giovanniandreuzza.nimbus

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogEvent
import io.github.giovanniandreuzza.nimbus.presentation.RetryPolicy
import io.github.giovanniandreuzza.nimbus.testing.InMemoryStorage
import io.github.giovanniandreuzza.nimbus.testing.RecordingLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the library does to a server that is down, with `autoStart` enabled.
 *
 * This loop is the reason an unattended device eventually gets its file: when a download's own
 * transport retries are spent and the task is `Failed`, the library brings it back. It used to
 * bring it back *immediately* — no wait, no counter, no ceiling. Measured before this change,
 * a port answering 503 was asked two hundred times inside half a second of virtual time, each
 * round costing a HEAD and a GET. On a metered cellular link, across a fleet that all lost the
 * same backend at the same moment and had nothing to spread them out, that is the failure mode
 * that turns one outage into two.
 *
 * The whole file must eventually arrive, so the answer is not to give up: it is to wait longer
 * each time, up to a ceiling, and to keep waiting for as long as it takes.
 */
class AutoRetryBackoffTest {

    @Test
    fun `a server that is down is asked again but slower each time`() = runTest {
        val net = AlwaysFailingPort()
        val f = fixture(net, RetryPolicy(maxAttempts = null, baseDelayMs = 1_000L, maxDelayMs = 30_000L))

        f.api.enqueueDownload(URL, PATH, NAME)
        advanceTimeBy(60_000L)
        f.scope.cancel()

        // Nominal waits are 1s, 2s, 4s, 8s, 16s, 30s… — with ±20 % of spread, the first
        // minute cannot hold more than a handful of attempts however the dice land.
        assertTrue(
            net.attempts in 2..9,
            "a minute of a dead backend should cost single-digit attempts, saw ${net.attempts}"
        )

        val scheduled = f.logger.events.filterIsInstance<NimbusLogEvent.AutoRetryScheduled>()
        assertTrue(scheduled.size >= 2, "each retry has to say when it will happen: $scheduled")
        assertEquals(
            scheduled.map { it.attempt },
            scheduled.indices.map { it + 1 },
            "the attempt number has to count, or the wait cannot grow"
        )
        // Each wait against its own nominal value rather than against the one before it: the
        // last two are both at the ceiling, and ±20 % of the same number can land either way
        // round. Asserting order there would be asserting the dice.
        scheduled.forEachIndexed { index, event ->
            val nominal = minOf(1_000L shl index, 30_000L)
            assertTrue(
                event.delayMs in (nominal * 8 / 10)..(nominal * 12 / 10),
                "wait ${index + 1} should be ${nominal} ms ±20 %, was ${event.delayMs} ms " +
                        "(all of them: ${scheduled.map { it.delayMs }})"
            )
        }
    }

    @Test
    fun `nothing is retried before its wait has passed`() = runTest {
        val net = AlwaysFailingPort()
        val f = fixture(net, RetryPolicy(maxAttempts = null, baseDelayMs = 10_000L, maxDelayMs = 60_000L))

        f.api.enqueueDownload(URL, PATH, NAME)
        // Ten seconds of nominal wait, less its spread: nothing can be due yet.
        advanceTimeBy(7_000L)
        f.scope.cancel()

        assertEquals(
            1,
            net.attempts,
            "the retry fired inside its own back-off, which is the same as having none"
        )
    }

    @Test
    fun `a capped policy stops and says so`() = runTest {
        // Not the default — the default keeps trying, because on a kiosk giving up is a
        // technician's visit. A caller who does want a limit needs to be told when it is hit.
        val net = AlwaysFailingPort()
        val f = fixture(net, RetryPolicy(maxAttempts = 2, baseDelayMs = 1_000L, maxDelayMs = 5_000L))

        f.api.enqueueDownload(URL, PATH, NAME)
        advanceTimeBy(600_000L)
        f.scope.cancel()

        assertEquals(3, net.attempts, "the first attempt plus the two retries allowed")
        val exhausted = f.logger.events.filterIsInstance<NimbusLogEvent.AutoRetryExhausted>()
        assertEquals(1, exhausted.size, "the end of the budget is an event, not a silence")
        assertEquals(2, exhausted.single().attempts)
    }

    @Test
    fun `a download that finishes forgets what it was counting`() = runTest {
        // A device up for months must not meet its next transient failure already at the
        // longest back-off.
        val net = FailThenSucceedPort(failuresBeforeSuccess = 1)
        val f = fixture(net, RetryPolicy(maxAttempts = null, baseDelayMs = 1_000L, maxDelayMs = 60_000L))

        f.api.enqueueDownload(URL, PATH, NAME)
        advanceTimeBy(120_000L)
        // Second file, same fixture: its first retry must be the first wait again.
        net.failNext()
        f.api.enqueueDownload(OTHER_URL, OTHER_PATH, NAME)
        advanceTimeBy(120_000L)
        f.scope.cancel()

        val waits = f.logger.events
            .filterIsInstance<NimbusLogEvent.AutoRetryScheduled>()
            .map { it.attempt }
        assertEquals(
            listOf(1, 1),
            waits,
            "each url counts for itself, and a finish clears the count: $waits"
        )
    }

    private fun TestScope.fixture(port: NimbusDownloadPort, autoRetry: RetryPolicy): Fixture {
        val logger = RecordingLogger()
        // The auto-retry loop is unbounded by design, so the scope has to be stopped when the
        // test is done watching it — otherwise the virtual clock never runs out of work. It is
        // also what an app is expected to do with the scope it supplies.
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val api = Nimbus.Builder()
            .withNimbusDownloadPort(port)
            .withNimbusStoragePort(InMemoryStorage())
            .withDownloadManagerPath("/nimbus/store")
            .withDownloadScope(scope)
            .withIODispatcher(StandardTestDispatcher(testScheduler))
            .withAutoStart(true)
            // The transport layer is not what is under test here: one attempt, then the task
            // fails and the loop above takes over.
            .withTransportRetry(RetryPolicy(maxAttempts = 0, baseDelayMs = 1L, maxDelayMs = 1L))
            .withAutoRetry(autoRetry)
            .withNimbusLogger(logger)
            .build()
            .init()
        return Fixture(api, logger, scope)
    }

    private class Fixture(
        val api: io.github.giovanniandreuzza.nimbus.presentation.NimbusAPI,
        val logger: RecordingLogger,
        val scope: CoroutineScope
    )

    private companion object {
        const val URL = "https://example.com/asset.bin"
        const val PATH = "/tmp/nimbus/asset.bin"
        const val OTHER_URL = "https://example.com/other.bin"
        const val OTHER_PATH = "/tmp/nimbus/other.bin"
        const val NAME = "asset.bin"
        const val SIZE = 1_024L
    }

    /** A backend that is simply down. */
    private class AlwaysFailingPort : NimbusDownloadPort {
        var attempts: Int = 0
            private set

        override suspend fun getFileSize(fileUrl: String): KResult<Long, GetFileSizeError> =
            Success(SIZE)

        override suspend fun downloadFile(
            fileUrl: String,
            offset: Long,
            onSourceOpened: suspend (Source) -> Unit
        ): KResult<Unit, DownloadError> {
            attempts++
            return Failure(
                DownloadError.TemporaryError(TemporaryDownloadErrorCause.ServerError(503))
            )
        }
    }

    /** Fails the next request, then serves the file. */
    private class FailThenSucceedPort(private var failuresBeforeSuccess: Int) :
        NimbusDownloadPort {

        fun failNext() {
            failuresBeforeSuccess = 1
        }

        override suspend fun getFileSize(fileUrl: String): KResult<Long, GetFileSizeError> =
            Success(SIZE)

        override suspend fun downloadFile(
            fileUrl: String,
            offset: Long,
            onSourceOpened: suspend (Source) -> Unit
        ): KResult<Unit, DownloadError> {
            if (failuresBeforeSuccess > 0) {
                failuresBeforeSuccess--
                return Failure(
                    DownloadError.TemporaryError(TemporaryDownloadErrorCause.ServerError(503))
                )
            }
            onSourceOpened(
                Buffer().apply { write(ByteArray((SIZE - offset).toInt())) }
            )
            return Success(Unit)
        }
    }
}
