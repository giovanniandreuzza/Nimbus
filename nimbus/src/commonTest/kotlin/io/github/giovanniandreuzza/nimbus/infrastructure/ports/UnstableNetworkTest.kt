package io.github.giovanniandreuzza.nimbus.infrastructure.ports

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.core.application.errors.PermanentDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadProgressCallback
import io.github.giovanniandreuzza.nimbus.infrastructure.digest.ContentDigest
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm
import io.github.giovanniandreuzza.nimbus.testing.InMemoryStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What a download looks like on the link Nimbus is actually built for: an appliance on a
 * congested cell, a file far larger than the window between drops, and nobody present.
 *
 * The single-interruption cases are covered elsewhere. These are about what happens when the
 * link keeps failing — whether resume composes across many attempts, whether it costs the
 * remainder of the file or the whole of it each time, and whether the bytes that survive all
 * that are the right ones.
 */
class UnstableNetworkTest {

    // -- resume across repeated drops ---------------------------------------

    @Test
    fun `a file that drops six times still arrives byte for byte`() = runTest {
        val net = HostileNetwork(CONTENT) { attempt, _ ->
            if (attempt <= 6) Behaviour.Deliver(bytes = 700) else Behaviour.Complete
        }
        val h = Harness(this, net, maxRetryAttempts = 10)

        h.run()

        assertTrue(h.finished, "expected the download to survive the drops: ${h.failure}")
        assertContentEquals(
            CONTENT,
            h.storage.read(PATH),
            "the bytes that survived repeated resumes are not the ones that were sent"
        )
    }

    @Test
    fun `resuming costs the remainder of the file rather than the whole of it`() = runTest {
        val net = HostileNetwork(CONTENT) { attempt, _ ->
            if (attempt <= 6) Behaviour.Deliver(bytes = 700) else Behaviour.Complete
        }
        val h = Harness(this, net, maxRetryAttempts = 10)

        h.run()

        assertTrue(h.finished, "expected the download to finish: ${h.failure}")
        // Seven attempts. Restarting each time would have cost seven files.
        assertTrue(
            net.bytesServed < CONTENT.size * 2L,
            "resume transferred ${net.bytesServed} bytes for a ${CONTENT.size}-byte file over " +
                    "${net.attempts} attempts; it is restarting rather than resuming"
        )
        assertTrue(
            net.bytesServed >= CONTENT.size.toLong(),
            "the whole file has to cross the wire at least once, saw ${net.bytesServed}"
        )
    }

    @Test
    fun `every attempt after the first asks to resume from what is already on disk`() = runTest {
        val net = HostileNetwork(CONTENT) { attempt, _ ->
            if (attempt <= 3) Behaviour.Deliver(bytes = 500) else Behaviour.Complete
        }
        val h = Harness(this, net, maxRetryAttempts = 10)

        h.run()

        assertEquals(listOf(0L, 500L, 1_000L, 1_500L), net.offsets.take(4))
    }

    // -- the digest under the same conditions --------------------------------

    @Test
    fun `the digest of a file that dropped repeatedly matches the one that did not`() = runTest {
        val straight = Harness(this, HostileNetwork(CONTENT) { _, _ -> Behaviour.Complete })
            .also { it.run() }
        val interrupted = Harness(
            this,
            HostileNetwork(CONTENT) { attempt, _ ->
                if (attempt <= 5) Behaviour.Deliver(bytes = 613) else Behaviour.Complete
            },
            maxRetryAttempts = 10
        ).also { it.run() }

        assertTrue(interrupted.finished, "expected a finish: ${interrupted.failure}")
        assertNotNull(straight.checksum, "the straight-through run produced no digest")
        assertEquals(
            straight.checksum,
            interrupted.checksum,
            "a digest accumulated per attempt would hash only the last stretch and be " +
                    "well-formed, plausible and wrong"
        )
    }

    @Test
    fun `a server that ignores the resume offset is caught on length`() = runTest {
        // The body arrives from byte 0 again and is appended to what is already there, so the
        // file overshoots. The cheap check catches this one before the digest is consulted.
        val net = HostileNetwork(CONTENT) { attempt, _ ->
            if (attempt == 1) Behaviour.Deliver(bytes = 1_000) else Behaviour.IgnoreOffset
        }
        val h = Harness(this, net, maxRetryAttempts = 1)

        h.run()

        val failure = h.failure ?: fail("a corrupted resume must not be reported as a success")
        assertTrue(
            failure is DownloadError.TemporaryError &&
                    failure.errorCause is TemporaryDownloadErrorCause.FileIntegrityMismatch,
            "expected the length check to catch it, got $failure"
        )
    }

    @Test
    fun `a resume that returns the right number of wrong bytes is caught only by the digest`() =
        runTest {
            // The length works out exactly, so nothing about the file's shape is wrong. This
            // is the case the size check cannot see and the digest exists for.
            val net = HostileNetwork(CONTENT) { attempt, _ ->
                if (attempt == 1) Behaviour.Deliver(bytes = 1_000) else Behaviour.Corrupt
            }
            val h = Harness(this, net, maxRetryAttempts = 1, expectedChecksum = digestOf(CONTENT))

            h.run()

            val failure = h.failure ?: fail("wrong bytes of the right length were accepted")
            assertTrue(
                failure is DownloadError.TemporaryError &&
                        failure.errorCause is TemporaryDownloadErrorCause.ChecksumMismatch,
                "expected the digest to catch it, got $failure"
            )
        }

    // -- when it never recovers ----------------------------------------------

    @Test
    fun `a link that never recovers gives up after the budget and keeps what it got`() =
        runTest {
            val net = HostileNetwork(CONTENT) { _, _ -> Behaviour.Deliver(bytes = 400) }
            val h = Harness(this, net, maxRetryAttempts = 3)

            h.run()

            val failure = h.failure ?: fail("expected a failure once the budget ran out")
            assertTrue(
                failure is DownloadError.TemporaryError,
                "a link that was down is still temporary when the budget ends, got $failure"
            )
            assertEquals(4, net.attempts, "one attempt plus the budget of three")
            assertTrue(
                (h.storage.read(PATH)?.size ?: 0) > 0,
                "the partial must survive so a later retry resumes rather than restarts"
            )
        }

    @Test
    fun `a permanent failure ends it immediately even after transient ones`() = runTest {
        val net = HostileNetwork(CONTENT) { attempt, _ ->
            if (attempt <= 2) Behaviour.Deliver(bytes = 300)
            else Behaviour.Fail(
                DownloadError.PermanentError(PermanentDownloadErrorCause.ResourceNotFound)
            )
        }
        val h = Harness(this, net, maxRetryAttempts = 10)

        h.run()

        assertTrue(
            h.failure is DownloadError.PermanentError,
            "the resource going away ends it, got ${h.failure}"
        )
        assertEquals(
            3,
            net.attempts,
            "a permanent answer must stop the loop rather than spend the remaining budget"
        )
    }

    // -- edges ----------------------------------------------------------------

    @Test
    fun `a link that dies before a single byte arrives still makes progress on the next try`() =
        runTest {
            val net = HostileNetwork(CONTENT) { attempt, _ ->
                if (attempt == 1) Behaviour.Deliver(bytes = 0) else Behaviour.Complete
            }
            val h = Harness(this, net, maxRetryAttempts = 3)

            h.run()

            assertTrue(h.finished, "expected a finish: ${h.failure}")
            assertContentEquals(CONTENT, h.storage.read(PATH))
        }

    @Test
    fun `a link that dies on the last byte finishes on the retry`() = runTest {
        val net = HostileNetwork(CONTENT) { attempt, _ ->
            if (attempt == 1) Behaviour.Deliver(bytes = CONTENT.size - 1) else Behaviour.Complete
        }
        val h = Harness(this, net, maxRetryAttempts = 3)

        h.run()

        assertTrue(h.finished, "expected a finish: ${h.failure}")
        assertContentEquals(CONTENT, h.storage.read(PATH))
    }

    @Test
    fun `a trickle of one byte at a time still completes`() = runTest {
        val net = HostileNetwork(CONTENT, chunk = 1) { _, _ -> Behaviour.Complete }
        val h = Harness(this, net)

        h.run()

        assertTrue(h.finished, "expected a finish: ${h.failure}")
        assertContentEquals(CONTENT, h.storage.read(PATH))
    }

    // -- harness ---------------------------------------------------------------

    private class Harness(
        private val scope: TestScope,
        val net: HostileNetwork,
        maxRetryAttempts: Int = 3,
        private val expectedChecksum: Checksum? = null
    ) {
        val storage = InMemoryStorage()
        var finished = false
            private set
        var failure: DownloadError? = null
            private set
        var checksum: Checksum? = null
            private set

        private val callback = object : DownloadProgressCallback {
            override suspend fun onDownloadProgress(id: String, progress: Double) = Unit
            override suspend fun onDownloadFailed(id: String, error: DownloadError) {
                failure = error
            }

            override suspend fun onDownloadFinished(id: String, checksum: Checksum?) {
                finished = true
                this@Harness.checksum = checksum
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
            maxRetryAttempts = maxRetryAttempts,
            retryBaseDelayMs = 1L,
            digestAlgorithm = DigestAlgorithm.SHA256
        )

        suspend fun run() {
            adapter.startDownload(
                DownloadTaskDTO(
                    id = ID,
                    fileName = ID,
                    fileUrl = "https://example.com/$ID",
                    filePath = PATH,
                    fileSize = net.content.size.toLong(),
                    state = DownloadState.Downloading(0.0),
                    expectedChecksum = expectedChecksum
                )
            )
            scope.advanceUntilIdle()
        }
    }

    private companion object {
        /** What the bytes actually hash to, which is what a caller would have been told. */
        fun digestOf(bytes: ByteArray): Checksum =
            ContentDigest(DigestAlgorithm.SHA256)
                .apply { update(bytes, 0, bytes.size) }
                .finish()

        const val ID = "task-0"
        const val PATH = "/tmp/nimbus/big.bin"

        /** Large enough that a drop leaves a meaningful prefix, and not uniform. */
        val CONTENT = ByteArray(5_000) { (it * 31 % 251).toByte() }
    }
}

// -----------------------------------------------------------------------------

private sealed interface Behaviour {
    /** Serve this many bytes from the requested offset, then the connection dies. */
    data class Deliver(val bytes: Int) : Behaviour

    /** Serve the rest of the file. */
    data object Complete : Behaviour

    /** Answer the resume with the body from byte 0, the way a server without Range does. */
    data object IgnoreOffset : Behaviour

    /** Serve exactly the bytes still missing, but not the right ones. */
    data object Corrupt : Behaviour

    data class Fail(val error: DownloadError) : Behaviour
}

/**
 * A network that behaves as badly as the test asks it to, and counts what it cost.
 *
 * [bytesServed] is the point of most of this: a resume that is really a restart still
 * finishes, still produces the right file, and is only distinguishable by how much crossed
 * the wire to get there.
 */
private class HostileNetwork(
    val content: ByteArray,
    private val chunk: Int = 256,
    private val behaviour: (attempt: Int, offset: Long) -> Behaviour
) : NimbusDownloadPort {

    var attempts: Int = 0
        private set
    var bytesServed: Long = 0L
        private set
    val offsets = mutableListOf<Long>()

    override suspend fun getFileSize(fileUrl: String): KResult<Long, GetFileSizeError> =
        Success(content.size.toLong())

    override suspend fun downloadFile(
        fileUrl: String,
        offset: Long,
        onSourceOpened: suspend (Source) -> Unit
    ): KResult<Unit, DownloadError> {
        attempts++
        offsets.add(offset)

        return when (val what = behaviour(attempts, offset)) {
            is Behaviour.Fail -> io.github.giovanniandreuzza.explicitarchitecture
                .shared.utilities.Failure(what.error)

            is Behaviour.Complete -> {
                onSourceOpened(serve(from = offset.toInt(), limit = Int.MAX_VALUE, dies = false))
                Success(Unit)
            }

            is Behaviour.IgnoreOffset -> {
                onSourceOpened(serve(from = 0, limit = Int.MAX_VALUE, dies = false))
                Success(Unit)
            }

            is Behaviour.Corrupt -> {
                val missing = (content.size - offset.toInt()).coerceAtLeast(0)
                onSourceOpened(Buffer().apply { write(ByteArray(missing) { 0x7F }) })
                bytesServed += missing.toLong()
                Success(Unit)
            }

            is Behaviour.Deliver -> {
                onSourceOpened(serve(from = offset.toInt(), limit = what.bytes, dies = true))
                Success(Unit)
            }
        }
    }

    private fun serve(from: Int, limit: Int, dies: Boolean): Source =
        object : RawSource {
            private var position = from.coerceIn(0, content.size)
            private var served = 0

            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
                if (served >= limit) {
                    if (dies) throw IOException("Connection reset by peer")
                    return -1L
                }
                if (position >= content.size) return -1L

                val n = minOf(
                    byteCount.toInt(),
                    chunk,
                    limit - served,
                    content.size - position
                )
                if (n <= 0) {
                    if (dies) throw IOException("Connection reset by peer")
                    return -1L
                }
                sink.write(content, position, position + n)
                position += n
                served += n
                bytesServed += n.toLong()
                return n.toLong()
            }

            override fun close() = Unit
        }.buffered()
}
