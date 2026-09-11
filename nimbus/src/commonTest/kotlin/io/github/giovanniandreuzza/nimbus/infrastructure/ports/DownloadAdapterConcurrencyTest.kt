package io.github.giovanniandreuzza.nimbus.infrastructure.ports

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.core.application.errors.PermanentDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadProgressCallback
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.testing.InMemoryStorage
import io.github.giovanniandreuzza.nimbus.testing.digestPortFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The adapter's two jobs beyond moving bytes: how many transfers may run at once, and how
 * many times a failing one is tried again.
 *
 * Both are invisible from the public API and both are the kind of thing a refactor breaks
 * without any test noticing — a semaphore acquired in the wrong place still downloads the
 * file, it just downloads forty of them at once, which on a constrained device is the
 * difference between working and falling over.
 */
class DownloadAdapterConcurrencyTest {

    @Test
    fun `the concurrency limit caps how many transfers run at once`() = runTest {
        val h = harness(concurrencyLimit = 2)
        val port = GatedPort(CONTENT)

        val adapter = h.adapter(port)
        repeat(4) { adapter.startDownload(task("task-$it")) }
        advanceUntilIdle()

        assertEquals(2, port.inFlight, "expected the semaphore to hold the other two back")

        port.release()
        advanceUntilIdle()

        assertEquals(4, port.completedRequests, "every download should eventually run")
        assertEquals(2, port.maxInFlight, "the limit must hold for the whole run")
    }

    @Test
    fun `a transient failure is retried up to the budget and then reported`() = runTest {
        val h = harness()
        val port = AlwaysFailingPort(
            DownloadError.TemporaryError(TemporaryDownloadErrorCause.ServerError(503))
        )

        h.adapter(port, maxRetryAttempts = 3).startDownload(task(ID))
        advanceUntilIdle()

        assertEquals(4, port.attempts, "expected the first attempt plus three retries")
        val failure = assertNotNull(h.failures.firstOrNull())
        assertTrue(
            failure is DownloadError.TemporaryError,
            "the error the caller sees is the one the transport gave up on: got $failure"
        )
        assertEquals(1, h.failures.size, "the failure is reported once, not once per attempt")
    }

    @Test
    fun `a permanent failure is not retried at all`() = runTest {
        val h = harness()
        val port = AlwaysFailingPort(
            DownloadError.PermanentError(PermanentDownloadErrorCause.ResourceNotFound)
        )

        h.adapter(port, maxRetryAttempts = 3).startDownload(task(ID))
        advanceUntilIdle()

        assertEquals(1, port.attempts, "retrying a 404 only wastes the device's battery")
        assertTrue(h.failures.firstOrNull() is DownloadError.PermanentError)
    }

    @Test
    fun `starting the same download twice does not open a second transfer`() = runTest {
        val h = harness()
        val port = GatedPort(CONTENT)
        val adapter = h.adapter(port)

        adapter.startDownload(task(ID))
        adapter.startDownload(task(ID))
        advanceUntilIdle()

        assertEquals(1, port.startedRequests, "the second start must find the job registered")

        port.release()
        advanceUntilIdle()

        assertEquals(1, h.finished.size)
    }

    @Test
    fun `stopping a download in flight ends it without a failure`() = runTest {
        val h = harness()
        val port = GatedPort(CONTENT)
        val adapter = h.adapter(port)

        adapter.startDownload(task(ID))
        advanceUntilIdle()
        assertEquals(1, port.inFlight, "the transfer has to be running for this to mean anything")

        adapter.stopDownload(ID)
        advanceUntilIdle()

        assertTrue(h.finished.isEmpty(), "a stopped download did not finish")
        assertTrue(
            h.failures.isEmpty(),
            "a download the caller stopped is not a failure to report: ${h.failures}"
        )
    }

    @Test
    fun `a file already complete on disk finishes without asking the network`() = runTest {
        val h = harness()
        val port = GatedPort(CONTENT)
        h.storage.write(PATH_OF_ID, CONTENT)

        h.adapter(port).startDownload(task(ID))
        advanceUntilIdle()

        assertEquals(listOf(ID), h.finished)
        assertEquals(0, port.startedRequests, "there was nothing left to transfer")
    }

    // -- harness -----------------------------------------------------------

    private fun TestScope.harness(concurrencyLimit: Int = 1) = Harness(this, concurrencyLimit)

    private class Harness(
        private val scope: TestScope,
        private val concurrencyLimit: Int
    ) {
        val storage = InMemoryStorage()
        val failures = mutableListOf<DownloadError>()
        val finished = mutableListOf<String>()

        private val callback = object : DownloadProgressCallback {
            override suspend fun onDownloadProgress(id: String, progress: Double) = Unit

            override suspend fun onDownloadFailed(id: String, error: DownloadError) {
                failures.add(error)
            }

            override suspend fun onDownloadFinished(id: String, checksum: Checksum?) {
                finished.add(id)
            }
        }

        fun adapter(port: NimbusDownloadPort, maxRetryAttempts: Int = 0) = DownloadAdapter(
            concurrencyLimit = concurrencyLimit,
            downloadScope = CoroutineScope(
                SupervisorJob() + StandardTestDispatcher(scope.testScheduler)
            ),
            downloadProgressCallback = callback,
            nimbusStoragePort = storage,
            nimbusDownloadPort = port,
            bufferSize = 64L,
            notifyEveryBytes = 128L,
            maxRetryAttempts = maxRetryAttempts,
            retryBaseDelayMs = 1L,
            digestAlgorithm = null,
                contentDigestPort = digestPortFor(storage)
        )
    }

    private fun task(id: String) = DownloadTaskDTO(
        id = id,
        fileName = id,
        fileUrl = "https://example.com/$id",
        filePath = "/tmp/nimbus/$id",
        fileSize = CONTENT.size.toLong(),
        state = DownloadState.Downloading(0.0)
    )

    private companion object {
        const val ID = "task-0"
        const val PATH_OF_ID = "/tmp/nimbus/task-0"
        val CONTENT = ByteArray(256) { (it * 13 % 251).toByte() }
    }
}

/**
 * Holds every transfer open until [release], so a test can look at how many are running.
 */
private class GatedPort(private val content: ByteArray) : NimbusDownloadPort {

    private val gate = CompletableDeferred<Unit>()

    var inFlight: Int = 0
        private set
    var maxInFlight: Int = 0
        private set
    var startedRequests: Int = 0
        private set
    var completedRequests: Int = 0
        private set

    fun release() {
        gate.complete(Unit)
    }

    override suspend fun getFileSize(fileUrl: String): KResult<Long, GetFileSizeError> =
        Success(content.size.toLong())

    override suspend fun downloadFile(
        fileUrl: String,
        offset: Long,
        onSourceOpened: suspend (Source) -> Unit
    ): KResult<Unit, DownloadError> {
        startedRequests++
        inFlight++
        if (inFlight > maxInFlight) maxInFlight = inFlight
        try {
            gate.await()
            val from = offset.toInt().coerceIn(0, content.size)
            onSourceOpened(Buffer().apply { write(content, from, content.size) })
            completedRequests++
            return Success(Unit)
        } finally {
            inFlight--
        }
    }
}

/** Counts how many times the adapter was willing to try. */
private class AlwaysFailingPort(private val error: DownloadError) : NimbusDownloadPort {

    var attempts: Int = 0
        private set

    override suspend fun getFileSize(fileUrl: String): KResult<Long, GetFileSizeError> =
        Success(1_024L)

    override suspend fun downloadFile(
        fileUrl: String,
        offset: Long,
        onSourceOpened: suspend (Source) -> Unit
    ): KResult<Unit, DownloadError> {
        attempts++
        return Failure(error)
    }
}
