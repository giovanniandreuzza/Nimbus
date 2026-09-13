package io.github.giovanniandreuzza.nimbus.infrastructure.ports

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadProgressCallback
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.RemoteFile
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.RetryPolicy
import io.github.giovanniandreuzza.nimbus.testing.InMemoryStorage
import io.github.giovanniandreuzza.nimbus.testing.MidJitter
import io.github.giovanniandreuzza.nimbus.testing.digestPortFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The case the stall guard must leave alone: a link that is slow, not dead.
 *
 * This one cannot live in `commonTest`. The waiting has to happen *inside a read* — that is
 * where a real transport waits — and `RawSource.readAtMostTo` is not a suspending function,
 * so the only way to model it is to block the thread, as Ktor's channel-backed source does.
 * A virtual-time scheduler would report the delay as instant and prove nothing; so this runs
 * on real threads and real milliseconds, and is deliberately small.
 *
 * A congested cell delivering a buffer every so often, on an appliance that has all night, is
 * the normal condition this library was built for. Killing it would be worse than the bug the
 * guard fixes.
 */
class SlowLinkNotStalledTest {

    @Test
    fun `a transfer that keeps delivering below the deadline is never abandoned`() = runBlocking {
        val storage = InMemoryStorage()
        val net = SlowNetwork(CONTENT, chunk = 64, pauseMs = 40L)
        val done = CompletableDeferred<Unit>()
        var failure: DownloadError? = null
        var finished = false

        val callback = object : DownloadProgressCallback {
            override suspend fun onDownloadProgress(id: String, progress: Double) = Unit

            override suspend fun onDownloadFailed(id: String, error: DownloadError) {
                failure = error
                done.complete(Unit)
            }

            override suspend fun onDownloadFinished(id: String, checksum: Checksum?) {
                finished = true
                done.complete(Unit)
            }
        }

        val adapter = DownloadAdapter(
            concurrencyLimit = 1,
            downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            downloadProgressCallback = callback,
            nimbusStoragePort = storage,
            nimbusDownloadPort = net,
            bufferSize = 64L,
            notifyEveryBytes = 128L,
            transportRetry = RetryPolicy(
                maxAttempts = 0,
                baseDelayMs = 1L,
                // Flat rather than exponential: these scenarios are about what is
                // retried, not about how long the waiting takes.
                maxDelayMs = 1L
            ),
            random = MidJitter,
            // Well above the 40 ms between chunks, well below the ~640 ms the whole transfer
            // takes: a deadline on elapsed time would fail this test, a deadline on progress
            // passes it.
            stallTimeoutMs = 400L,
            digestAlgorithm = null,
            contentDigestPort = digestPortFor(storage)
        )

        adapter.startDownload(
            DownloadTaskDTO(
                id = ID,
                fileName = ID,
                fileUrl = "https://example.com/$ID",
                filePath = PATH,
                fileSize = CONTENT.size.toLong(),
                state = DownloadState.Downloading(0.0)
            )
        )
        withTimeout(30_000) { done.await() }

        assertTrue(finished, "a slow transfer must be allowed to finish, got $failure")
        assertContentEquals(CONTENT, storage.read(PATH), "and to deliver the right bytes")
        assertEquals(1, net.attempts, "with no retry, because nothing went wrong")
    }

    private companion object {
        const val ID = "task-slow"
        const val PATH = "/tmp/nimbus/slow.bin"
        val CONTENT = ByteArray(1_024) { (it * 13 % 251).toByte() }
    }
}

/** Hands over the body a chunk at a time, blocking between chunks the way a socket does. */
private class SlowNetwork(
    private val content: ByteArray,
    private val chunk: Int,
    private val pauseMs: Long
) : NimbusDownloadPort {

    var attempts: Int = 0
        private set

    override suspend fun getRemoteFile(fileUrl: String): KResult<RemoteFile, GetFileSizeError> =
        Success(RemoteFile(content.size.toLong()))

    override suspend fun downloadFile(
        fileUrl: String,
        offset: Long,
        resumeValidator: String?,
        onSourceOpened: suspend (Source) -> Unit
    ): KResult<Unit, DownloadError> {
        attempts++
        onSourceOpened(TricklingRawSource(content, offset.toInt(), chunk, pauseMs).buffered())
        return Success(Unit)
    }
}

private class TricklingRawSource(
    private val content: ByteArray,
    private var position: Int,
    private val chunk: Int,
    private val pauseMs: Long
) : RawSource {

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        if (position >= content.size) return -1L
        // The wait belongs here, before the bytes: this is a read waiting on a network, which
        // is the only place a real transfer ever stalls.
        Thread.sleep(pauseMs)
        val n = minOf(byteCount.toInt(), chunk, content.size - position)
        sink.write(content, position, position + n)
        position += n
        return n.toLong()
    }

    override fun close() = Unit
}
