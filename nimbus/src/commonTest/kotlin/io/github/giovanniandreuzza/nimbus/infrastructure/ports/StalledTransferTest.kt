package io.github.giovanniandreuzza.nimbus.infrastructure.ports

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryGetFileSizeErrorCause
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadProgressCallback
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.testing.InMemoryStorage
import io.github.giovanniandreuzza.nimbus.testing.digestPortFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A connection that goes quiet and never says so.
 *
 * Every other failure in this library announces itself. A stall does not: the headers
 * arrived, the response is still open, and to every layer below the adapter the download is
 * still in progress. The task stays `Downloading`, the concurrency permit stays taken — with
 * the default limit of one, that is the entire queue — and nothing is emitted or logged. A
 * kiosk in that state stops updating its content and reports perfect health.
 *
 * These tests are about telling that apart from the thing it resembles: a link that is merely
 * slow. The guard measures progress, never elapsed time, so the distinction is the whole
 * design.
 */
class StalledTransferTest {

    @Test
    fun `a transfer that stops delivering is abandoned rather than waited on forever`() =
        runTest {
            val net = QuietNetwork(deliverBeforeGoingQuiet = 500)
            val h = Harness(this, net, stallTimeoutMs = STALL, maxRetryAttempts = 1)

            h.run()

            val failure = h.failure ?: fail("a stalled transfer must not be left in progress")
            assertTrue(
                failure is DownloadError.TemporaryError,
                "a link that went quiet is worth retrying, got $failure"
            )
            val cause = (failure as DownloadError.TemporaryError).errorCause
            assertTrue(
                cause is TemporaryDownloadErrorCause.TransportFailure,
                "a stall is a transport failure, not an integrity or storage one: got $cause"
            )
            assertEquals(
                "transfer_stalled",
                (cause as TemporaryDownloadErrorCause.TransportFailure).cause.code,
                "the cause has to name the stall, or nobody reading a log learns what happened"
            )
        }

    @Test
    fun `the partial bytes survive the stall so the retry resumes rather than restarts`() =
        runTest {
            val net = QuietNetwork(deliverBeforeGoingQuiet = 500)
            val h = Harness(this, net, stallTimeoutMs = STALL, maxRetryAttempts = 1)

            h.run()

            assertEquals(
                500,
                h.storage.read(PATH)?.size,
                "what arrived before the link went quiet is a valid prefix and must be kept"
            )
            assertEquals(
                listOf(0L, 500L),
                net.offsets,
                "the second attempt has to ask for the bytes still missing"
            )
        }

    @Test
    fun `each stalled attempt costs the timeout and no more`() = runTest {
        val net = QuietNetwork(deliverBeforeGoingQuiet = 500)
        val h = Harness(this, net, stallTimeoutMs = STALL, maxRetryAttempts = 1)

        h.run()

        assertEquals(2, net.attempts, "one attempt plus the budget of one")
        // Two stalls, plus the retry back-off between them. Without the guard this test would
        // not finish at all.
        assertEquals(
            STALL * 2 + RETRY_DELAY,
            currentTime,
            "a stalled attempt must be abandoned at the deadline, not before or long after"
        )
    }

    @Test
    fun `a body that arrived in full is finished even if the transport then goes quiet`() =
        runTest {
            // The bytes are all there and the port hangs on its way out. Abandoning the
            // attempt is right; asking for more of a complete file is not — a resume from the
            // end of it is answered with a 416, and a 416 makes this adapter throw away
            // everything it just paid for.
            val net = QuietNetwork(deliverBeforeGoingQuiet = CONTENT.size)
            val h = Harness(this, net, stallTimeoutMs = STALL, maxRetryAttempts = 3)

            h.run()

            assertTrue(h.finished, "a complete file must be reported finished: ${h.failure}")
            assertEquals(CONTENT.size, h.storage.read(PATH)?.size, "and must not be truncated")
            assertEquals(
                1,
                net.attempts,
                "nothing is left to transfer, so nothing may be requested again"
            )
        }

    @Test
    fun `a stalled size request fails instead of suspending the caller`() = runTest {
        // getFileSize runs on the caller's coroutine — an enqueue, or a reconciliation loop
        // walking a manifest. A server that accepts the connection and then says nothing
        // suspends that caller, not a download.
        val h = Harness(this, QuietSizeNetwork(), stallTimeoutMs = STALL, maxRetryAttempts = 0)

        val result = h.adapter.getFileSizeToDownload(URL)

        val error = when (result) {
            is Failure -> result.error
            is Success -> fail("the size request never answered; it must not report a size")
        }
        val cause = (error as GetFileSizeError.TemporaryError).errorCause
        assertEquals(
            "size_request_stalled",
            (cause as TemporaryGetFileSizeErrorCause.TransportFailure).cause.code
        )
        assertEquals(STALL, currentTime)
    }

    @Test
    fun `pausing a quiet transfer still unwinds as a pause`() = runTest {
        // The watchdog cancels the transfer to abandon it, and a pause cancels the job the
        // same way. If the two were confused, every pause would be reported as a failed
        // download — and with autoStart on, immediately retried.
        val net = QuietNetwork(deliverBeforeGoingQuiet = 500)
        val h = Harness(this, net, stallTimeoutMs = STALL, maxRetryAttempts = 5)

        h.start()
        // Short of the deadline on purpose: advancing past it would let the watchdog fire and
        // the test would be asserting about a stall rather than about a pause.
        advanceTimeBy(STALL / 2)
        h.adapter.stopDownload(ID)
        advanceUntilIdle()

        assertEquals(
            null,
            h.failure,
            "a paused download is not a failed one; nothing may be reported for it"
        )
        assertTrue(!h.finished, "and it certainly did not finish")
    }

    // -- harness ---------------------------------------------------------------

    private class Harness(
        private val scope: TestScope,
        private val net: NimbusDownloadPort,
        stallTimeoutMs: Long?,
        maxRetryAttempts: Int
    ) {
        val storage = InMemoryStorage()
        var finished = false
            private set
        var failure: DownloadError? = null
            private set

        private val callback = object : DownloadProgressCallback {
            override suspend fun onDownloadProgress(id: String, progress: Double) = Unit
            override suspend fun onDownloadFailed(id: String, error: DownloadError) {
                failure = error
            }

            override suspend fun onDownloadFinished(id: String, checksum: Checksum?) {
                finished = true
            }
        }

        val adapter = DownloadAdapter(
            concurrencyLimit = 1,
            downloadScope = CoroutineScope(
                SupervisorJob() + StandardTestDispatcher(scope.testScheduler)
            ),
            downloadProgressCallback = callback,
            nimbusStoragePort = storage,
            nimbusDownloadPort = net,
            bufferSize = 256L,
            notifyEveryBytes = 512L,
            maxRetryAttempts = maxRetryAttempts,
            retryBaseDelayMs = RETRY_DELAY,
            stallTimeoutMs = stallTimeoutMs,
            digestAlgorithm = null,
            contentDigestPort = digestPortFor(storage)
        )

        suspend fun start() {
            adapter.startDownload(
                DownloadTaskDTO(
                    id = ID,
                    fileName = ID,
                    fileUrl = URL,
                    filePath = PATH,
                    fileSize = CONTENT.size.toLong(),
                    state = DownloadState.Downloading(0.0)
                )
            )
        }

        suspend fun run() {
            start()
            scope.advanceUntilIdle()
        }
    }

    private companion object {
        const val ID = "task-quiet"
        const val URL = "https://example.com/task-quiet"
        const val PATH = "/tmp/nimbus/quiet.bin"
        const val STALL = 5_000L
        const val RETRY_DELAY = 1L
        val CONTENT = ByteArray(2_000) { (it * 17 % 251).toByte() }
    }
}

/**
 * Delivers a prefix and then keeps the response open forever without another byte.
 *
 * `awaitCancellation` rather than a blocking wait: a port that suspends is one the watchdog
 * can actually unwind, which is the contract every `NimbusDownloadPort` is asked to meet.
 * Ktor's channel-backed source blocks a thread instead, which is why `KtorDownloadAdapter`
 * imposes a socket timeout — no watchdog anywhere can interrupt a blocked read.
 */
private class QuietNetwork(private val deliverBeforeGoingQuiet: Int) : NimbusDownloadPort {

    var attempts: Int = 0
        private set
    val offsets = mutableListOf<Long>()

    override suspend fun getFileSize(fileUrl: String): KResult<Long, GetFileSizeError> =
        Success(2_000L)

    override suspend fun downloadFile(
        fileUrl: String,
        offset: Long,
        onSourceOpened: suspend (Source) -> Unit
    ): KResult<Unit, DownloadError> {
        attempts++
        offsets.add(offset)
        if (deliverBeforeGoingQuiet > 0 && offset == 0L) {
            onSourceOpened(Buffer().apply { write(ByteArray(deliverBeforeGoingQuiet)) })
        } else {
            onSourceOpened(Buffer())
        }
        awaitCancellation()
    }
}

/** Never answers the size request at all. */
private class QuietSizeNetwork : NimbusDownloadPort {
    override suspend fun getFileSize(fileUrl: String): KResult<Long, GetFileSizeError> =
        awaitCancellation()

    override suspend fun downloadFile(
        fileUrl: String,
        offset: Long,
        onSourceOpened: suspend (Source) -> Unit
    ): KResult<Unit, DownloadError> = awaitCancellation()
}
