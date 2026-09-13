package io.github.giovanniandreuzza.nimbus.infrastructure.repositories.mappers

import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage.ChecksumStore
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage.DownloadStateStore
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage.DownloadTaskStore
import io.github.giovanniandreuzza.nimbus.infrastructure.repositories.mappers.DownloadTaskStoreMappers.toDomain
import io.github.giovanniandreuzza.nimbus.infrastructure.repositories.mappers.DownloadTaskStoreMappers.toStore
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What comes back out of a store written by an older build.
 *
 * Until 2.5.0 `Checksum`'s constructor took any string, so a store can hold `"abc"` where a
 * digest belongs. Restoring that produces a value no transfer can ever match — and a mismatch
 * is temporary, so the file is fetched again on every pass, for ever. The endless re-download
 * the digest exists to prevent, arriving through the store.
 */
class DownloadTaskStoreMappersTest {

    @Test
    fun `a digest that could never be one is dropped`() {
        val restored = storedTask(
            expectedChecksum = ChecksumStore(algorithm = "SHA256", value = "abc")
        ).toDomain()

        assertNull(
            restored.expectedChecksum,
            "a check that could never pass is not a check being skipped"
        )
    }

    @Test
    fun `a digest under an algorithm this build does not know is dropped`() {
        val restored = storedTask(
            checksum = ChecksumStore(algorithm = "SHA512", value = "a".repeat(128))
        ).toDomain()

        assertNull(restored.checksum, "reporting it as SHA256 would fail every comparison")
    }

    @Test
    fun `a digest that is one survives the round trip`() {
        val expected = Checksum.of(DigestAlgorithm.SHA256, "a".repeat(64))

        val restored = storedTask(
            expectedChecksum = ChecksumStore(algorithm = "SHA256", value = "a".repeat(64))
        ).toDomain()

        assertEquals(expected, restored.expectedChecksum)
        assertEquals(
            "a".repeat(64),
            restored.toStore().expectedChecksum?.value,
            "and goes back to disk unchanged"
        )
    }

    private fun storedTask(
        expectedChecksum: ChecksumStore? = null,
        checksum: ChecksumStore? = null
    ) = DownloadTaskStore(
        id = "task-1",
        fileName = "clip.mp4",
        fileUrl = "https://example.com/clip.mp4",
        filePath = "/files/nimbus/clip.mp4",
        fileSize = 1_024L,
        state = DownloadStateStore.Finished,
        expectedChecksum = expectedChecksum,
        checksum = checksum
    )
}
