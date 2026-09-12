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
import io.github.giovanniandreuzza.nimbus.testing.InMemoryStorage
import io.github.giovanniandreuzza.nimbus.testing.digestPortFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The volume filling up while the bytes are being written.
 *
 * Headroom is checked before a transfer starts and never again, so the interesting case is
 * the one the check cannot see: a device that had room when it began and ran out part-way,
 * which on an appliance sharing storage with logs and an app cache is the normal way to run
 * out of space.
 *
 * What matters here is not that it fails — it must — but what it says.
 *
 * The same real condition used to wear two faces: caught by the headroom check before a
 * transfer starts it was reported as insufficient space, and caught here it arrived as a
 * generic storage failure with the reason buried in a string. A caller that prunes its cache
 * and retries on the first would not have recognised the second.
 *
 * So a failed write now asks the volume how much room is left, and only calls it a shortage
 * when the volume says there is one — a write that failed for any other reason stays exactly
 * what it was.
 */
class DiskFullDuringTransferTest {

    @Test
    fun `a volume that fills up mid-transfer says so`() = runTest {
        val h = Harness(this)
        h.storage.onSink = { Success(h.failingSink()) }
        h.storage.usableSpace = 0L

        h.run()

        assertTrue(h.failures.isNotEmpty(), "running out of space has to fail the transfer")
        val cause = (h.failures.single() as DownloadError.PermanentError).errorCause
        assertTrue(
            cause is PermanentDownloadErrorCause.InsufficientDiskSpace,
            "a volume that refused the write because it is full has to say so, not hide it " +
                    "behind a generic storage failure that sends a reader looking at " +
                    "permissions; got $cause"
        )
        assertEquals(PATH, cause.path)
        assertEquals(0L, cause.availableBytes)
    }

    @Test
    fun `a write that fails for any other reason stays a storage error`() = runTest {
        val h = Harness(this)
        h.storage.onSink = { Success(h.failingSink()) }
        // The volume has room: whatever stopped the write, it was not space.
        h.storage.usableSpace = Long.MAX_VALUE

        h.run()

        val cause = (h.failures.single() as DownloadError.PermanentError).errorCause
        assertTrue(
            cause is PermanentDownloadErrorCause.StorageError,
            "only a genuine shortage may be reported as one; got $cause"
        )
    }

    private class Harness(private val scope: TestScope) {
        val storage = InMemoryStorage()
        val failures = mutableListOf<DownloadError>()

        /** A sink that accepts a little and then reports the volume as full. */
        fun failingSink(): Sink = object : RawSink {
            private var written = 0L

            override fun write(source: Buffer, byteCount: Long) {
                written += byteCount
                if (written > 32L) throw IOException("No space left on device")
                source.skip(byteCount)
            }

            override fun flush() = Unit
            override fun close() = Unit
        }.buffered()

        private val callback = object : DownloadProgressCallback {
            override suspend fun onDownloadProgress(id: String, progress: Double) = Unit
            override suspend fun onDownloadFailed(id: String, error: DownloadError) {
                failures.add(error)
            }

            override suspend fun onDownloadFinished(id: String, checksum: Checksum?) = Unit
        }

        suspend fun run() {
            val adapter = DownloadAdapter(
                concurrencyLimit = 1,
                downloadScope = CoroutineScope(
                    SupervisorJob() + StandardTestDispatcher(scope.testScheduler)
                ),
                downloadProgressCallback = callback,
                nimbusStoragePort = storage,
                nimbusDownloadPort = DeliveringPort(CONTENT),
                bufferSize = 16L,
                notifyEveryBytes = 32L,
                maxRetryAttempts = 1,
                retryBaseDelayMs = 1L,
                digestAlgorithm = null,
                contentDigestPort = digestPortFor(storage)
            )
            adapter.startDownload(
                DownloadTaskDTO(
                    id = "disk-full",
                    fileName = "payload.bin",
                    fileUrl = "https://example.com/payload.bin",
                    filePath = PATH,
                    fileSize = CONTENT.size.toLong(),
                    state = DownloadState.Downloading(0.0)
                )
            )
            scope.advanceUntilIdle()
        }
    }

    private class DeliveringPort(private val content: ByteArray) : NimbusDownloadPort {
        override suspend fun getFileSize(fileUrl: String): KResult<Long, GetFileSizeError> =
            Success(content.size.toLong())

        override suspend fun downloadFile(
            fileUrl: String,
            offset: Long,
            onSourceOpened: suspend (Source) -> Unit
        ): KResult<Unit, DownloadError> {
            val buffer = Buffer().apply { write(content, offset.toInt(), content.size) }
            onSourceOpened(buffer)
            return Success(Unit)
        }
    }

    private companion object {
        const val PATH = "/tmp/nimbus/payload.bin"
        val CONTENT = ByteArray(256) { it.toByte() }
    }
}
