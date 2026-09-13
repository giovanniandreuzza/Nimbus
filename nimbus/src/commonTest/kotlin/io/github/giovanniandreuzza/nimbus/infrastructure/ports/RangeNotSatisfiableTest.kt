package io.github.giovanniandreuzza.nimbus.infrastructure.ports

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
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
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.CreateFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.DeleteFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.RemoteFile
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What happens after HTTP 416.
 *
 * A server answers 416 when the byte range asked for no longer exists — the file was
 * replaced, or the local prefix is longer than what is now being served. The only way
 * forward is to throw away the local bytes and start again from zero, so the adapter
 * deletes the file and recreates it.
 *
 * The two storage calls in that path each have a failure mode, and they are not
 * interchangeable. A delete that genuinely failed used to be discarded: the create that
 * followed then reported `FileAlreadyExists`, which the adapter reads as two writers
 * colliding and retries as [TemporaryDownloadErrorCause.TruncateRace]. The download
 * usually recovered, which is why it went unnoticed — but the retry was attributed to a
 * race that never happened, and the permission or I/O error that actually stopped it never
 * reached the logger. These tests hold the two apart.
 */
class RangeNotSatisfiableTest {

    @Test
    fun `a 416 throws away the local bytes and transfers the file from the start`() = runTest {
        val h = harness()
        h.storage.write(PATH, CONTENT.copyOf(40))

        h.run(RangeRejectingPort(CONTENT, rejectFirstRequest = true))

        assertTrue(h.finished, "expected the download to finish, failures: ${h.failures}")
        assertContentEquals(
            CONTENT,
            h.storage.read(PATH),
            "the restarted transfer must leave the whole file, not append to the stale prefix"
        )
    }

    @Test
    fun `a delete that genuinely failed is reported as the storage error it was`() = runTest {
        val h = harness()
        h.storage.write(PATH, CONTENT.copyOf(40))
        h.storage.onDelete = {
            Failure(DeleteFileError.DeletePermissionDenied(KError("eperm", "read-only volume")))
        }

        h.run(RangeRejectingPort(CONTENT, rejectFirstRequest = true))

        val failure = assertNotNull(h.failures.firstOrNull(), "expected the failure to surface")
        assertTrue(
            failure is DownloadError.PermanentError &&
                    failure.errorCause is PermanentDownloadErrorCause.StorageError,
            "a failed delete is a storage problem, not a race: got $failure"
        )
        assertTrue(
            h.failures.none {
                it is DownloadError.TemporaryError &&
                        it.errorCause is TemporaryDownloadErrorCause.TruncateRace
            },
            "the delete failure must not be relabelled as a truncate race: ${h.failures}"
        )
    }

    @Test
    fun `a file that was already gone is not a delete failure`() = runTest {
        val h = harness()
        h.storage.write(PATH, CONTENT.copyOf(40))
        // Something else removed the file between the 416 and the delete. The delete has
        // nothing to do and says so; the outcome the adapter wanted has happened anyway.
        h.storage.onDelete = { path ->
            h.storage.remove(path)
            Failure(DeleteFileError.FileNotFound)
        }

        h.run(RangeRejectingPort(CONTENT, rejectFirstRequest = true))

        assertTrue(h.finished, "expected the download to finish, failures: ${h.failures}")
        assertContentEquals(CONTENT, h.storage.read(PATH))
    }

    @Test
    fun `a file that reappears before the recreate is a truncate race`() = runTest {
        val h = harness()
        h.storage.write(PATH, CONTENT.copyOf(40))
        // The delete worked; by the time the create ran the path was taken again. That is
        // the collision TruncateRace names, and it is transient by nature.
        h.storage.onCreate = { Failure(CreateFileError.FileAlreadyExists) }

        h.run(RangeRejectingPort(CONTENT, rejectFirstRequest = true))

        val failure = assertNotNull(h.failures.firstOrNull(), "expected a failure")
        assertTrue(
            failure is DownloadError.TemporaryError &&
                    failure.errorCause is TemporaryDownloadErrorCause.TruncateRace,
            "got $failure"
        )
    }

    @Test
    fun `the first 416 does not consume the transport retry budget`() = runTest {
        // The first one is not a failing transfer — it is a transfer that has to start from a
        // different offset — so it truncates and restarts with the budget untouched. The two
        // that follow are budgeted like any other temporary failure (see the test below), and
        // a budget of two absorbs them, so the download still completes.
        val h = harness(maxRetryAttempts = 2)
        h.storage.write(PATH, CONTENT.copyOf(40))

        h.run(RangeRejectingPort(CONTENT, rejectFirstRequest = true, rejectCount = 3))

        assertTrue(h.finished, "expected the download to finish, failures: ${h.failures}")
        assertContentEquals(CONTENT, h.storage.read(PATH))
    }

    @Test
    fun `a range refused over and over gives up instead of truncating forever`() = runTest {
        // Measured before this change: five hundred consecutive 416s were five hundred
        // truncations, at full speed, with no delay between them and no end — each one a
        // delete and a create of the file, the CPU pinned, the task still reporting
        // `Downloading`. The truncation puts the request back at offset 0, which carries no
        // Range header at all; a server that answers *that* with a 416 will answer the next
        // one the same way, so treating the second as ordinary is what makes this terminate.
        val h = harness(maxRetryAttempts = 2)
        val port = RangeRejectingPort(CONTENT, rejectFirstRequest = true, rejectCount = 500)

        h.run(port)

        val failure = h.failures.lastOrNull()
            ?: throw AssertionError("a range that is never satisfiable has to be reported")
        assertTrue(
            failure is DownloadError.TemporaryError &&
                    failure.errorCause is TemporaryDownloadErrorCause.RangeNotSatisfiable,
            "and reported as what it is, got $failure"
        )
        assertEquals(
            4,
            port.requests,
            "one truncation, then the budget of two, and nothing more"
        )
    }

    @Test
    fun `a file that changed at the origin is thrown away and fetched again`() = runTest {
        // The transport reports it the way a 416 is reported, and the recovery is the same:
        // the local bytes came from a file that no longer exists at that url, so keeping them
        // would append the tail of one file to the head of another.
        val h = harness(digestAlgorithm = DigestAlgorithm.SHA256)
        h.storage.write(PATH, CONTENT.copyOf(40))

        h.run(ChangedFilePort(CONTENT))

        assertTrue(h.finished, "expected the download to finish, failures: ${h.failures}")
        assertContentEquals(CONTENT, h.storage.read(PATH))
        assertEquals(
            CONTENT_SHA256,
            h.finishedChecksum?.value,
            "the digest has to describe the file that was actually fetched, not the prefix " +
                    "that was discarded"
        )
    }

    @Test
    fun `an origin that keeps saying the file changed gives up instead of looping`() = runTest {
        // Same cap as the 416: the attempt after the truncation asks from offset 0 with no
        // validator to check, so an origin that answers *that* the same way will answer every
        // one the same way.
        val h = harness(maxRetryAttempts = 1)
        val port = ChangedFilePort(CONTENT, changedForever = true)

        h.run(port)

        val failure = h.failures.lastOrNull() ?: fail("it has to be reported")
        assertTrue(
            failure is DownloadError.TemporaryError &&
                    failure.errorCause is TemporaryDownloadErrorCause.RemoteFileChanged,
            "got $failure"
        )
        assertEquals(3, port.requests, "one truncation, then the budget of one, and no more")
    }

    // -- harness -----------------------------------------------------------

    @Test
    fun `the digest after a 416 describes the file that was actually kept`() = runTest {
        val h = harness(digestAlgorithm = DigestAlgorithm.SHA256)
        // A prefix that the server will reject, so the local bytes are thrown away and the
        // transfer restarts from zero.
        h.storage.write(PATH, CONTENT.copyOf(40))

        h.run(RangeRejectingPort(CONTENT, rejectFirstRequest = true))

        assertTrue(h.finished, "expected the download to finish, failures: ${h.failures}")
        assertEquals(
            CONTENT_SHA256,
            h.finishedChecksum?.value,
            "after a 416 the file on disk was truncated and fetched again from zero, so the " +
                    "digest has to describe exactly those bytes. A digest still carrying the " +
                    "discarded prefix would be well-formed, plausible and wrong — and a caller " +
                    "comparing it later would delete and refetch a perfectly good file forever"
        )
    }

    private fun TestScope.harness(
        maxRetryAttempts: Int = 3,
        digestAlgorithm: DigestAlgorithm? = null
    ) = Harness(this, maxRetryAttempts, digestAlgorithm)

    private class Harness(
        private val scope: TestScope,
        private val maxRetryAttempts: Int,
        private val digestAlgorithm: DigestAlgorithm? = null
    ) {
        val storage = InMemoryStorage()
        val failures = mutableListOf<DownloadError>()
        var finished = false
            private set
        var finishedChecksum: Checksum? = null
            private set

        private val callback = object : DownloadProgressCallback {
            override suspend fun onDownloadProgress(id: String, progress: Double) = Unit

            override suspend fun onDownloadFailed(id: String, error: DownloadError) {
                failures.add(error)
            }

            override suspend fun onDownloadFinished(id: String, checksum: Checksum?) {
                finished = true
                finishedChecksum = checksum
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
                bufferSize = 16L,
                notifyEveryBytes = 32L,
                transportRetry = RetryPolicy(
                    maxAttempts = maxRetryAttempts,
                    baseDelayMs = 1L,
                    // Flat rather than exponential: these scenarios are about what is
                    // retried, not about how long the waiting takes.
                    maxDelayMs = 1L
                ),
                random = MidJitter,
                // The stall guard has its own test; these scenarios all deliver or fail promptly.
                stallTimeoutMs = null,
                digestAlgorithm = digestAlgorithm,
                contentDigestPort = digestPortFor(storage)
            )
            adapter.startDownload(task())
            scope.advanceUntilIdle()
        }

        private fun task() = DownloadTaskDTO(
            id = ID,
            fileName = "payload.bin",
            fileUrl = "https://example.com/payload.bin",
            filePath = PATH,
            fileSize = CONTENT.size.toLong(),
            state = DownloadState.Downloading(0.0)
        )
    }

    private companion object {
        const val ID = "task"
        const val PATH = "/tmp/nimbus/payload.bin"
        val CONTENT = ByteArray(200) { (it * 7 % 251).toByte() }

        /** SHA-256 of CONTENT, computed outside this codebase so it is an independent oracle. */
        const val CONTENT_SHA256 =
            "fcfa8eb2ae47de09df3e42e48371d9ea7446fb378097f8ef9bf743d9856f50b6"
    }
}

/**
 * Answers the first [rejectCount] requests with 416, then serves the content from the
 * offset asked for.
 */
/** Answers a resume by saying the file is not the one the partial came from. */
private class ChangedFilePort(
    private val content: ByteArray,
    private val changedForever: Boolean = false
) : NimbusDownloadPort {

    var requests: Int = 0
        private set

    override suspend fun getRemoteFile(fileUrl: String): KResult<RemoteFile, GetFileSizeError> =
        Success(RemoteFile(content.size.toLong(), validator = "\"v2\""))

    override suspend fun downloadFile(
        fileUrl: String,
        offset: Long,
        resumeValidator: String?,
        onSourceOpened: suspend (Source) -> Unit
    ): KResult<Unit, DownloadError> {
        requests++
        if (changedForever || offset > 0L) {
            return Failure(
                DownloadError.TemporaryError(TemporaryDownloadErrorCause.RemoteFileChanged)
            )
        }
        onSourceOpened(Buffer().apply { write(content) })
        return Success(Unit)
    }
}

private class RangeRejectingPort(
    private val content: ByteArray,
    private val rejectFirstRequest: Boolean,
    private val rejectCount: Int = 1
) : NimbusDownloadPort {

    private var rejected = 0

    var requests: Int = 0
        private set

    override suspend fun getRemoteFile(fileUrl: String): KResult<RemoteFile, GetFileSizeError> =
        Success(RemoteFile(content.size.toLong()))

    override suspend fun downloadFile(
        fileUrl: String,
        offset: Long,
        resumeValidator: String?,
        onSourceOpened: suspend (Source) -> Unit
    ): KResult<Unit, DownloadError> {
        requests++
        if (rejectFirstRequest && rejected < rejectCount) {
            rejected++
            // A 416 carries no body, so nothing is written and nothing is opened.
            return Failure(
                DownloadError.TemporaryError(TemporaryDownloadErrorCause.RangeNotSatisfiable)
            )
        }

        val from = offset.toInt().coerceIn(0, content.size)
        onSourceOpened(Buffer().apply { write(content, from, content.size) })
        return Success(Unit)
    }
}
