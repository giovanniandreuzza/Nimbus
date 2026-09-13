package io.github.giovanniandreuzza.nimbus

import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Values the builder used to take and fail on later.
 *
 * A configuration mistake should be refused where it is written, not at the first transfer:
 * `withDownloadBufferSize(0)` became a loop that never advanced, and a buffer larger than an
 * `Int` became an allocation failure hours into a device's day. Both are one `require` away
 * from being a stack trace that names the option.
 */
class BuilderValidationTest {

    @Test
    fun `a buffer of nothing is refused`() {
        assertFailsWith<IllegalArgumentException> {
            Nimbus.Builder().withDownloadBufferSize(0L)
        }
    }

    @Test
    fun `a buffer larger than the library will ever allocate is refused`() {
        assertFailsWith<IllegalArgumentException> {
            Nimbus.Builder().withDownloadBufferSize(Nimbus.Builder.MAX_BUFFER_SIZE_BYTES + 1)
        }
    }

    @Test
    fun `a concurrency limit of zero is refused`() {
        // Semaphore(0) is a queue that never runs; the exception it threw named neither the
        // option nor the value.
        assertFailsWith<IllegalArgumentException> {
            Nimbus.Builder().withConcurrencyLimit(0)
        }
    }

    @Test
    fun `a progress notification interval of zero is refused`() {
        assertFailsWith<IllegalArgumentException> {
            Nimbus.Builder().withDownloadNotifyEveryBytes(0L)
        }
    }

    @Test
    fun `a stall timeout of zero is refused but null is not`() {
        assertFailsWith<IllegalArgumentException> {
            Nimbus.Builder().withStallTimeoutMs(0L)
        }
        Nimbus.Builder().withStallTimeoutMs(null)
    }

    @Test
    fun `a blank download root is refused`() {
        assertFailsWith<IllegalArgumentException> {
            Nimbus.Builder().withDownloadRoot("   ")
        }
    }

    @Test
    fun `a checksum has to be a digest of the right shape`() {
        // The constructor took whatever it was given, and `Checksum(SHA256, "abc")` compiled.
        // It is internal now, so `of` is the only way in and this is what it enforces.
        assertFailsWith<IllegalArgumentException> {
            Checksum.of(DigestAlgorithm.SHA256, "abc")
        }
        assertFailsWith<IllegalArgumentException> {
            Checksum.of(DigestAlgorithm.SHA256, "z".repeat(64))
        }
        assertEquals(
            "a".repeat(64),
            Checksum.of(DigestAlgorithm.SHA256, "A".repeat(64)).value,
            "hex is normalised, because a server that shouts its digest still means the same one"
        )
    }
}
