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
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.testing.InMemoryStorage
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
    fun `a 416 does not consume the transport retry budget`() = runTest {
        // Three 416s in a row, against a budget of two ordinary retries. A 416 resets the
        // attempt counter because it is not a failing transfer — it is a transfer that has
        // to start from a different offset — so the download still completes.
        val h = harness(maxRetryAttempts = 2)
        h.storage.write(PATH, CONTENT.copyOf(40))

        h.run(RangeRejectingPort(CONTENT, rejectFirstRequest = true, rejectCount = 3))

        assertTrue(h.finished, "expected the download to finish, failures: ${h.failures}")
        assertContentEquals(CONTENT, h.storage.read(PATH))
    }

    // -- harness -----------------------------------------------------------

    private fun TestScope.harness(maxRetryAttempts: Int = 3) = Harness(this, maxRetryAttempts)

    private class Harness(
        private val scope: TestScope,
        private val maxRetryAttempts: Int
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
                bufferSize = 16L,
                notifyEveryBytes = 32L,
                maxRetryAttempts = maxRetryAttempts,
                retryBaseDelayMs = 1L,
                digestAlgorithm = null
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
    }
}

/**
 * Answers the first [rejectCount] requests with 416, then serves the content from the
 * offset asked for.
 */
private class RangeRejectingPort(
    private val content: ByteArray,
    private val rejectFirstRequest: Boolean,
    private val rejectCount: Int = 1
) : NimbusDownloadPort {

    private var rejected = 0

    override suspend fun getFileSize(fileUrl: String): KResult<Long, GetFileSizeError> =
        Success(content.size.toLong())

    override suspend fun downloadFile(
        fileUrl: String,
        offset: Long,
        onSourceOpened: suspend (Source) -> Unit
    ): KResult<Unit, DownloadError> {
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
