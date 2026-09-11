package io.github.giovanniandreuzza.nimbus.infrastructure.ports

import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadProgressCallback
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.testing.InMemoryStorage
import io.github.giovanniandreuzza.nimbus.testing.digestPortFor
import kotlinx.io.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A file that is already on disk at the expected length.
 *
 * `startDownload` short-circuits this case: there is nothing to transfer, so it reports the
 * task finished without starting a job. That shortcut predates the content digest, and it
 * skipped every check the digest exists to perform — the file was accepted on its length
 * alone, no bytes were hashed, and a caller's `expectedChecksum` was never consulted.
 *
 * It is the scenario the feature was built for. A player that keeps its assets between runs
 * finds them already on disk at the right size on every start; if that path cannot tell a
 * file whose bytes changed from one that is intact, the digest answers the question only in
 * the case nobody needed it answered.
 */
class CompleteFileOnDiskTest {

    @Test
    fun `a complete file is rejected when its bytes do not match the expected checksum`() =
        runTest {
            val h = harness(this)
            h.storage.write(PATH, CONTENT)

            h.run(expectedChecksum = WRONG_CHECKSUM)

            assertFalse(h.finished, "a file whose bytes do not match must not report finished")
            assertTrue(
                h.failures.any {
                    it is DownloadError.TemporaryError &&
                            it.errorCause is TemporaryDownloadErrorCause.ChecksumMismatch
                },
                "expected ChecksumMismatch, got: ${h.failures}"
            )
        }

    @Test
    fun `a complete file finishes with the digest of what is on disk`() = runTest {
        val h = harness(this)
        h.storage.write(PATH, CONTENT)

        h.run(expectedChecksum = null)

        assertTrue(h.finished, "expected the download to finish, failures: ${h.failures}")
        val recorded = assertNotNull(
            h.finishedChecksum,
            "a digest-enabled task must record what the file hashed to"
        )
        assertEquals(DigestAlgorithm.SHA256, recorded.algorithm)
        assertEquals(CONTENT_SHA256, recorded.value)
    }

    @Test
    fun `a complete file finishes when its bytes match the expected checksum`() = runTest {
        val h = harness(this)
        h.storage.write(PATH, CONTENT)

        h.run(expectedChecksum = Checksum.of(DigestAlgorithm.SHA256, CONTENT_SHA256))

        assertTrue(h.finished, "expected the download to finish, failures: ${h.failures}")
        assertEquals(CONTENT_SHA256, h.finishedChecksum?.value)
    }

    private fun harness(scope: TestScope) = Harness(scope)

    private class Harness(private val scope: TestScope) {
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

        suspend fun run(expectedChecksum: Checksum?) {
            val adapter = DownloadAdapter(
                concurrencyLimit = 1,
                downloadScope = CoroutineScope(
                    SupervisorJob() + StandardTestDispatcher(scope.testScheduler)
                ),
                downloadProgressCallback = callback,
                nimbusStoragePort = storage,
                nimbusDownloadPort = neverCalled(),
                bufferSize = 16L,
                notifyEveryBytes = 32L,
                maxRetryAttempts = 1,
                retryBaseDelayMs = 1L,
                digestAlgorithm = DigestAlgorithm.SHA256,
                contentDigestPort = digestPortFor(storage)
            )
            adapter.startDownload(task(expectedChecksum))
            scope.advanceUntilIdle()
        }

        /** The file is already complete: nothing may be requested over the network. */
        private fun neverCalled(): NimbusDownloadPort = object : NimbusDownloadPort {
            override suspend fun getFileSize(fileUrl: String): KResult<Long, GetFileSizeError> =
                throw AssertionError("the file is already complete; no request may be made")

            override suspend fun downloadFile(
                fileUrl: String,
                offset: Long,
                onSourceOpened: suspend (Source) -> Unit
            ): KResult<Unit, DownloadError> =
                throw AssertionError("the file is already complete; no request may be made")
        }

        private fun task(expectedChecksum: Checksum?) = DownloadTaskDTO(
            id = ID,
            fileName = "payload.bin",
            fileUrl = "https://example.com/payload.bin",
            filePath = PATH,
            fileSize = CONTENT.size.toLong(),
            state = DownloadState.Downloading(0.0),
            expectedChecksum = expectedChecksum
        )
    }

    private companion object {
        const val ID = "complete-file"
        const val PATH = "/tmp/payload.bin"
        val CONTENT = ByteArray(64) { it.toByte() }

        /** SHA-256 of CONTENT, asserted rather than computed by the code under test. */
        const val CONTENT_SHA256 =
            "fdeab9acf3710362bd2658cdc9a29e8f9c757fcf9811603a8c447cd1d9151108"

        val WRONG_CHECKSUM = Checksum.of(
            DigestAlgorithm.SHA256,
            "0000000000000000000000000000000000000000000000000000000000000000"
        )
    }
}
