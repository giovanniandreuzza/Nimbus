package io.github.giovanniandreuzza.nimbus.infrastructure.ports

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.core.application.errors.PermanentDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadProgressCallback
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm
import io.github.giovanniandreuzza.nimbus.presentation.RetryPolicy
import io.github.giovanniandreuzza.nimbus.testing.InMemoryStorage
import io.github.giovanniandreuzza.nimbus.testing.MidJitter
import io.github.giovanniandreuzza.nimbus.testing.digestPortFor
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
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A server that sends more than it said it would.
 *
 * The declared size drives everything — the progress percentage, the disk headroom check, the
 * integrity check at the end — and until now it drove none of the writing. The loop followed
 * the body wherever it went and the length was compared afterwards, on a volume that had
 * already taken the bytes. An origin serving an HTML error page in place of a 200 MB asset, a
 * CDN looping a chunk, a file replaced between the size request and the transfer: any of them
 * could spend an eight-gigabyte appliance's entire volume on one task.
 *
 * Two bytes past the declared length is enough to know something is wrong, so that is where
 * the writing stops.
 */
class OversizedBodyTest {

    @Test
    fun `nothing past the declared size is read out of the body`() = runTest {
        val port = OversizedPort(declared = DECLARED, serve = DECLARED * 4)
        val h = Harness(this)

        h.run(port)

        assertEquals(
            DECLARED * 3,
            port.unreadBytes,
            "the declared bytes are taken and the rest is left on the wire: reading it is what " +
                    "filled the volume"
        )
    }

    @Test
    fun `the file is not left at a length that would pass for complete`() = runTest {
        // `startDownload` short-circuits when the destination already has the expected size.
        // The bytes here arrived under a contradiction — in a resume that the server answered
        // from byte zero, they are the wrong bytes at exactly the right length — so a file
        // left that way would be reported complete on the next start.
        val h = Harness(this)

        h.run(OversizedPort(declared = DECLARED, serve = DECLARED * 4))

        val onDisk = h.storage.read(PATH)?.size ?: 0
        assertTrue(
            onDisk < DECLARED,
            "expected the partial to be discarded, found $onDisk bytes of $DECLARED"
        )
    }

    @Test
    fun `a body that will not stop is reported as the server contradicting itself`() = runTest {
        val h = Harness(this)

        h.run(OversizedPort(declared = DECLARED, serve = DECLARED * 4))

        val failure = h.failures.lastOrNull() ?: fail("a truncated body must not report success")
        assertTrue(
            failure is DownloadError.PermanentError,
            "the next attempt asks the same question and gets the same answer, so retrying " +
                    "cannot converge: got $failure"
        )
        val cause = (failure as DownloadError.PermanentError).errorCause
        assertTrue(
            cause is PermanentDownloadErrorCause.BodyLongerThanDeclared,
            "and the cause has to name what happened, not blame local storage: got $cause"
        )
        assertEquals(DECLARED, cause.declaredBytes)
    }

    @Test
    fun `it is not retried`() = runTest {
        val port = OversizedPort(declared = DECLARED, serve = DECLARED * 4)
        val h = Harness(this)

        h.run(port)

        assertEquals(
            1,
            port.attempts,
            "a permanent cause spends no part of the retry budget, and each attempt would " +
                    "otherwise cost the whole oversized body again"
        )
    }

    @Test
    fun `the cap holds on the digesting path too`() = runTest {
        // The two paths read into different places — the sink's own buffer when nothing is
        // hashed, a reusable ByteArray when something is — so each needs its own bound.
        val port = OversizedPort(declared = DECLARED, serve = DECLARED * 4)
        val h = Harness(this, digestAlgorithm = DigestAlgorithm.SHA256)

        h.run(port)

        assertEquals(DECLARED * 3, port.unreadBytes)
        assertTrue(
            h.failures.lastOrNull() is DownloadError.PermanentError,
            "got ${h.failures}"
        )
    }

    @Test
    fun `a body of exactly the declared size is untouched by the cap`() = runTest {
        val h = Harness(this)

        h.run(OversizedPort(declared = DECLARED, serve = DECLARED))

        assertTrue(h.finished, "the ordinary case must still finish: ${h.failures}")
        assertEquals(DECLARED.toInt(), h.storage.read(PATH)?.size)
    }

    private class Harness(
        private val scope: TestScope,
        private val digestAlgorithm: DigestAlgorithm? = null
    ) {
        val storage = InMemoryStorage()
        val failures = mutableListOf<DownloadError>()
        var finished = false
            private set

        private val callback = object : DownloadProgressCallback {
            override suspend fun onDownloadProgress(id: String, progress: Double) = Unit
            override suspend fun onDownloadFailed(id: String, error: DownloadError) {
                failures.add(error)
            }

            override suspend fun onDownloadFinished(id: String, checksum: Checksum?) {
                finished = true
            }
        }

        suspend fun run(port: NimbusDownloadPort) {
            val adapter = DownloadAdapter(
                concurrencyLimit = 1,
                downloadScope = CoroutineScope(
                    SupervisorJob() + StandardTestDispatcher(scope.testScheduler)
                ),
                downloadProgressCallback = callback,
                nimbusStoragePort = storage,
                nimbusDownloadPort = port,
                bufferSize = 64L,
                notifyEveryBytes = 128L,
                transportRetry = RetryPolicy(
                    maxAttempts = 3,
                    baseDelayMs = 1L,
                    maxDelayMs = 1L
                ),
                random = MidJitter,
                stallTimeoutMs = null,
                digestAlgorithm = digestAlgorithm,
                contentDigestPort = digestPortFor(storage)
            )
            adapter.startDownload(
                DownloadTaskDTO(
                    id = ID,
                    fileName = "asset.bin",
                    fileUrl = "https://example.com/asset.bin",
                    filePath = PATH,
                    fileSize = DECLARED,
                    state = DownloadState.Downloading(0.0)
                )
            )
            scope.advanceUntilIdle()
        }
    }

    private companion object {
        const val ID = "task-oversized"
        const val PATH = "/tmp/nimbus/oversized.bin"
        const val DECLARED = 1_000L
    }
}

/**
 * Announces [declared] bytes and hands over [serve] of them.
 *
 * The body is kept so a test can see how much of it was actually taken: what the writing stops
 * at is the property under test, and once the partial is discarded the disk cannot show it.
 */
private class OversizedPort(
    private val declared: Long,
    private val serve: Long
) : NimbusDownloadPort {

    private var body: Buffer = Buffer()

    var attempts: Int = 0
        private set

    /** What the transfer left on the wire. */
    val unreadBytes: Long get() = body.size

    override suspend fun getFileSize(fileUrl: String): KResult<Long, GetFileSizeError> =
        Success(declared)

    override suspend fun downloadFile(
        fileUrl: String,
        offset: Long,
        onSourceOpened: suspend (Source) -> Unit
    ): KResult<Unit, DownloadError> {
        attempts++
        val remaining = (serve - offset).coerceAtLeast(0L).toInt()
        body = Buffer().apply { write(ByteArray(remaining) { 0x41 }) }
        onSourceOpened(body)
        return Success(Unit)
    }
}
