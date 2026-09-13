package io.github.giovanniandreuzza.nimbus.infrastructure.ports

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadProgressCallback
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm
import io.github.giovanniandreuzza.nimbus.testing.InMemoryStorage
import io.github.giovanniandreuzza.nimbus.testing.digestPortFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * A source that says bytes are waiting and then hands over none.
 *
 * `kotlinx.io` should not do this, but the transfer loop is the hot path of a library that
 * runs unattended for months on hardware nobody will visit, and it used to answer a
 * zero-length read by going round again — against a source whose answer never changes. That
 * is not a slow download, it is a process pinned to a core until someone notices.
 *
 * The loop leaves instead. The attempt then ends with fewer bytes than the declared length,
 * which is a condition the library already knows how to describe.
 */
class ZeroLengthReadTest {

    @Test
    fun `a source that returns no bytes ends the attempt instead of spinning`() =
        runTest(timeout = 10.seconds) {
        // Verified against the previous `continue`: the run does not fail, it hangs. The
        // spinning loop reaches no suspension point — `yield()` is only called when progress
        // is reported, and no bytes means no progress — so neither the timeout below nor
        // cancellation can interrupt it. A CI job times out; an appliance in the field just
        // burns a core. That is the shape of the bug, and why the guard is worth a test.
        val harness = Harness(this, StuckNetwork())

        harness.run()

        val failure = harness.failure
            ?: fail("a transfer that moved no bytes must not be reported as finished")
        assertTrue(
            failure is DownloadError.TemporaryError &&
                    failure.errorCause is TemporaryDownloadErrorCause.FileIntegrityMismatch,
            "a body that delivered nothing is a file short of its declared length, got $failure"
        )
        assertTrue(
            harness.attempts >= 1,
            "the body was never opened, so nothing about the loop was exercised"
        )
        assertTrue(!harness.finished, "nothing was transferred; this is not a finished download")
    }

    private class Harness(
        private val scope: kotlinx.coroutines.test.TestScope,
        private val net: StuckNetwork
    ) {
        val storage = InMemoryStorage()
        var failure: DownloadError? = null
            private set
        var finished = false
            private set

        val attempts: Int get() = net.attempts

        private val callback = object : DownloadProgressCallback {
            override suspend fun onDownloadProgress(id: String, progress: Double) = Unit
            override suspend fun onDownloadFailed(id: String, error: DownloadError) {
                failure = error
            }

            override suspend fun onDownloadFinished(id: String, checksum: Checksum?) {
                finished = true
            }
        }

        private val adapter = DownloadAdapter(
            concurrencyLimit = 1,
            downloadScope = CoroutineScope(
                SupervisorJob() + StandardTestDispatcher(scope.testScheduler)
            ),
            downloadProgressCallback = callback,
            nimbusStoragePort = storage,
            nimbusDownloadPort = net,
            bufferSize = 256L,
            notifyEveryBytes = 512L,
            maxRetryAttempts = 2,
            retryBaseDelayMs = 1L,
            digestAlgorithm = DigestAlgorithm.SHA256,
            contentDigestPort = digestPortFor(storage)
        )

        suspend fun run() {
            adapter.startDownload(
                DownloadTaskDTO(
                    id = ID,
                    fileName = ID,
                    fileUrl = "https://example.com/$ID",
                    filePath = PATH,
                    fileSize = SIZE,
                    state = DownloadState.Downloading(0.0)
                )
            )
            scope.advanceUntilIdle()
        }
    }

    private companion object {
        const val ID = "task-stuck"
        const val PATH = "/tmp/nimbus/stuck.bin"
        const val SIZE = 4_096L
    }
}

/**
 * Serves a source that is never exhausted and never produces a byte.
 *
 * Delegation to an empty [Buffer] gives the rest of the `Source` surface; only the two
 * answers the transfer loop acts on are overridden.
 */
private class StuckNetwork : NimbusDownloadPort {

    var attempts: Int = 0
        private set

    override suspend fun getFileSize(fileUrl: String): KResult<Long, GetFileSizeError> =
        Success(4_096L)

    override suspend fun downloadFile(
        fileUrl: String,
        offset: Long,
        onSourceOpened: suspend (Source) -> Unit
    ): KResult<Unit, DownloadError> {
        attempts++
        onSourceOpened(StuckSource().buffered())
        return Success(Unit)
    }
}

/**
 * `kotlinx.io.Source` is sealed, so the stuck answer is staged one layer down: a [RawSource]
 * that reads nothing without ever reporting the end of the stream. Buffered, that is a
 * `Source` whose `exhausted()` is false — it saw a read that was not -1 — and whose next read
 * hands the loop no bytes.
 */
private class StuckSource : RawSource {
    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = 0L
    override fun close() = Unit
}
