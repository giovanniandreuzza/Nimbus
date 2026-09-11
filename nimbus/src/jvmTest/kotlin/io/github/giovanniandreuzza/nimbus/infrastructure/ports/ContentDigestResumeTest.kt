package io.github.giovanniandreuzza.nimbus.infrastructure.ports

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadProgressCallback
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.adapters.storage.FileSystemNimbusStorageAdapter
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.Source
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The test that carries the design.
 *
 * The resume offset is the length of the file on disk, and nothing about it is held in
 * memory, so a resume survives a process restart. A digest accumulated only across a
 * streaming session would therefore cover only what that session transferred: after a
 * restart-and-resume it would hash the tail alone and produce a value that is well-formed,
 * plausible and wrong. Without these assertions, priming from disk is a claim rather than a
 * property.
 */
class ContentDigestResumeTest {

    @Test
    fun `a download that ran straight through digests to the content`() = runBlocking {
        val content = contentOf(200_000)
        val harness = Harness(content)

        val finished = harness.run(FakeDownloadPort(content))

        assertEquals(expectedDigest(content), finished?.value, "digest must match the content")
    }

    @Test
    fun `an interrupted and resumed download digests to the same value`() = runBlocking {
        val content = contentOf(200_000)
        val harness = Harness(content)

        val finished = harness.run(FakeDownloadPort(content, cutAfterBytes = 73_000))

        assertEquals(
            expectedDigest(content),
            finished?.value,
            "a resume must not hash the tail alone"
        )
    }

    @Test
    fun `a resume by a process that kept no memory of the first attempt digests the same`() =
        runBlocking {
            val content = contentOf(200_000)
            val harness = Harness(content)

            // A process killed mid-transfer leaves exactly this behind: a prefix on disk
            // and nothing else. No adapter that could have carried hash state forward ever
            // existed here, so the digest has only the file to work from.
            harness.writePartial(content, bytes = 73_000)

            val finished = harness.run(FakeDownloadPort(content))

            assertEquals(
                expectedDigest(content),
                finished?.value,
                "the digest must reflect the whole file, not what this process transferred"
            )
        }

    @Test
    fun `a supplied checksum that does not match is temporary and is retried`() = runBlocking {
        val content = contentOf(50_000)
        val wrong = Checksum(DigestAlgorithm.SHA256, "0".repeat(64))
        val harness = Harness(content, expectedChecksum = wrong)

        harness.run(FakeDownloadPort(content), expectFinish = false)

        val failure = harness.failures.firstOrNull()
        assertNotNull(failure, "expected the mismatch to be reported")
        assertTrue(
            failure is DownloadError.TemporaryError &&
                    failure.errorCause is TemporaryDownloadErrorCause.ChecksumMismatch,
            "a mismatch is a statement about the transfer, not the file: got $failure"
        )
    }

    @Test
    fun `a supplied checksum that matches finishes and is reported back`() = runBlocking {
        val content = contentOf(50_000)
        val harness = Harness(content, expectedChecksum = expectedDigest(content))

        val finished = harness.run(FakeDownloadPort(content))

        assertEquals(expectedDigest(content), finished?.value)
        assertTrue(harness.failures.isEmpty(), "expected no failure, got ${harness.failures}")
    }

    @Test
    fun `a size mismatch reports the size cause, not the checksum cause`() = runBlocking {
        val content = contentOf(50_000)
        // The task expects more bytes than the server will ever send, and the checksum the
        // caller supplied cannot match either. The more specific cause has to win.
        val harness = Harness(
            content,
            declaredSize = content.size + 1_000L,
            expectedChecksum = Checksum(DigestAlgorithm.SHA256, "0".repeat(64))
        )

        harness.run(FakeDownloadPort(content), expectFinish = false)

        val failure = harness.failures.firstOrNull()
        assertNotNull(failure, "expected a failure")
        assertTrue(
            failure is DownloadError.TemporaryError &&
                    failure.errorCause is TemporaryDownloadErrorCause.FileIntegrityMismatch,
            "a truncated transfer must report the size cause: got $failure"
        )
    }

    @Test
    fun `with no algorithm configured nothing is hashed`() = runBlocking {
        val content = contentOf(50_000)
        val harness = Harness(content, algorithm = null)

        val finished = harness.run(FakeDownloadPort(content))

        assertNotNull(finished, "the download should still finish")
        assertNull(finished.value, "no algorithm means no checksum is computed")
    }

    @Test
    fun `a file many buffers long digests correctly`() = runBlocking {
        // 8 MB against an 8 KB buffer: a thousand buffer boundaries, and a resume landing
        // in the middle of them. Asserts the chunked feed is correct across boundaries; it
        // does not measure allocation, which a unit test cannot do reliably.
        val content = contentOf(8 * 1024 * 1024)
        val harness = Harness(content)
        harness.writePartial(content, bytes = 3_000_001)

        val finished = harness.run(FakeDownloadPort(content))

        assertEquals(expectedDigest(content), finished?.value)
    }

    private fun contentOf(size: Int) = ByteArray(size) { (it * 31 % 251).toByte() }

    private fun expectedDigest(content: ByteArray): Checksum {
        val hex = MessageDigest.getInstance("SHA-256").digest(content)
            .joinToString("") { byte -> "%02x".format(byte) }
        return Checksum(DigestAlgorithm.SHA256, hex)
    }
}

/**
 * Drives a real [DownloadAdapter] against a real filesystem and a scripted download port.
 */
private class Harness(
    private val content: ByteArray,
    private val declaredSize: Long = content.size.toLong(),
    private val expectedChecksum: Checksum? = null,
    private val algorithm: DigestAlgorithm? = DigestAlgorithm.SHA256
) {
    private val dir = Files.createTempDirectory("nimbus-digest").toFile()
    private val filePath = dir.resolve("payload.bin").absolutePath
    private val storage = FileSystemNimbusStorageAdapter()

    val failures = CopyOnWriteArrayList<DownloadError>()
    private val finishedChecksums = CopyOnWriteArrayList<Box>()

    private val callback = object : DownloadProgressCallback {
        override suspend fun onDownloadProgress(id: String, progress: Double) = Unit

        override suspend fun onDownloadFailed(id: String, error: DownloadError) {
            failures.add(error)
        }

        override suspend fun onDownloadFinished(id: String, checksum: Checksum?) {
            finishedChecksums.add(Box(checksum))
        }
    }

    /** Leaves a prefix of [content] on disk, as an interrupted transfer would. */
    fun writePartial(content: ByteArray, bytes: Int) {
        java.io.File(filePath).writeBytes(content.copyOf(bytes))
    }

    /** Builds a fresh adapter each time, so no state carries between runs. */
    suspend fun run(port: NimbusDownloadPort, expectFinish: Boolean = true): Box? =
        execute(adapterOn(port), expectFinish)

    private fun adapterOn(port: NimbusDownloadPort) = DownloadAdapter(
        concurrencyLimit = 1,
        downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        downloadProgressCallback = callback,
        nimbusStoragePort = storage,
        nimbusDownloadPort = port,
        bufferSize = 8 * 1024L,
        notifyEveryBytes = 64 * 1024L,
        maxRetryAttempts = 3,
        retryBaseDelayMs = 1L,
        digestAlgorithm = algorithm
    )

    private suspend fun execute(adapter: DownloadAdapter, expectFinish: Boolean): Box? {
        val before = finishedChecksums.size
        val failuresBefore = failures.size
        adapter.startDownload(task())

        val settled = withTimeoutOrNull(10_000) {
            while (finishedChecksums.size == before && failures.size == failuresBefore) delay(5)
            true
        }
        if (settled == null) fail("the download neither finished nor failed")

        if (!expectFinish) return null
        if (finishedChecksums.size == before) fail("expected the download to finish, got $failures")
        return finishedChecksums.last()
    }

    private fun task() = DownloadTaskDTO(
        id = "task",
        fileName = "payload.bin",
        fileUrl = "https://example.com/payload.bin",
        filePath = filePath,
        fileSize = declaredSize,
        state = DownloadState.Enqueued,
        expectedChecksum = expectedChecksum
    )
}

/** Distinguishes "finished with no checksum" from "did not finish". */
internal class Box(val value: Checksum?)

/**
 * Serves [content] from the requested offset.
 *
 * @param cutAfterBytes stop short after this many bytes of the first response and report a
 * transient failure, which is what an interrupted transfer looks like.
 */
private class FakeDownloadPort(
    private val content: ByteArray,
    private val cutAfterBytes: Int? = null
) : NimbusDownloadPort {

    private var served = 0

    override suspend fun getFileSize(fileUrl: String): KResult<Long, GetFileSizeError> =
        Success(content.size.toLong())

    override suspend fun downloadFile(
        fileUrl: String,
        offset: Long,
        onSourceOpened: suspend (Source) -> Unit
    ): KResult<Unit, DownloadError> {
        val from = offset.toInt().coerceIn(0, content.size)
        val cut = cutAfterBytes
        val shouldCut = cut != null && served == 0
        val to = if (shouldCut) minOf(from + cut, content.size) else content.size
        served++

        onSourceOpened(Buffer().apply { write(content, from, to) })

        return if (shouldCut && to < content.size) {
            Failure(
                DownloadError.TemporaryError(TemporaryDownloadErrorCause.FileIntegrityMismatch)
            )
        } else {
            Success(Unit)
        }
    }
}
