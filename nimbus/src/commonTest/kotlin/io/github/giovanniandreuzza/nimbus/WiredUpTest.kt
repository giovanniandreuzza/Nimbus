package io.github.giovanniandreuzza.nimbus

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm
import io.github.giovanniandreuzza.nimbus.testing.InMemoryStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The library as an integrator gets it: built through [Nimbus.Builder], wired by the DI
 * module, driven through [io.github.giovanniandreuzza.nimbus.presentation.NimbusAPI].
 *
 * Every other test here builds its subject directly and hands it fakes, which is what makes
 * them sharp — and leaves the container itself covered by nothing. Nothing would have caught
 * a port constructed after the component that needs it, a logger that never arrives, or a
 * dispatcher wired to the wrong place: the parts would each pass their own tests and the
 * assembled library would not work.
 *
 * So this one asserts almost nothing about internals and only that the whole thing, put
 * together the documented way, downloads a file and can then say what it downloaded.
 */
class WiredUpTest {

    @Test
    fun `a library built the documented way downloads a file and reports its digest`() =
        runTest {
            val storage = InMemoryStorage()
            val nimbus = Nimbus.Builder()
                .withDownloadScope(
                    CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
                )
                .withIODispatcher(StandardTestDispatcher(testScheduler))
                .withNimbusStoragePort(storage)
                .withNimbusDownloadPort(DeliveringPort(CONTENT))
                .withDownloadManagerPath(STORE_PATH)
                .withContentDigest(DigestAlgorithm.SHA256)
                .createAndInit()

            val enqueued = nimbus.enqueueDownload(URL, DESTINATION, FILE_NAME)
            advanceUntilIdle()
            assertTrue(enqueued is Success<*>, "enqueue failed: $enqueued")

            nimbus.startDownload(URL)
            advanceUntilIdle()

            val taskResult = nimbus.getDownloadTask(URL)
            val task = (taskResult as? Success)?.value
                ?: throw AssertionError("the task vanished: $taskResult")
            assertEquals(DownloadState.Finished, task.state, "task was $task")
            assertEquals(
                CONTENT_SHA256,
                task.checksum?.value,
                "a digest-enabled library has to record what it downloaded"
            )

            val recomputed = nimbus.checksum(URL)
            assertEquals(
                CONTENT_SHA256,
                (recomputed as? Success)?.value?.value,
                "re-reading the file from disk has to agree with what was recorded; got $recomputed"
            )

            assertTrue(nimbus.isDownloaded(URL), "the file is on disk and the task is finished")
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
        const val URL = "https://example.com/payload.bin"
        /** The full destination path, which is what filePath means — not the directory. */
        const val DESTINATION = "/tmp/nimbus/payload.bin"
        const val FILE_NAME = "payload.bin"
        const val STORE_PATH = "/tmp/nimbus/store"
        val CONTENT = ByteArray(512) { (it * 3 % 251).toByte() }

        /** Computed outside this codebase, so the library is measured against an oracle. */
        const val CONTENT_SHA256 =
            "1b5e977203fcc1a3c30d788b1ab8344f8f931edbea51883ef37c32c142d8947a"
    }
}
