package io.github.giovanniandreuzza.nimbus.infrastructure.digest

import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm
import kotlinx.io.Source
import org.kotlincrypto.hash.sha2.SHA256

/**
 * An incremental content digest.
 *
 * Fed the bytes of a file in order, in whatever sized pieces the caller has, and asked for
 * the result once. Holds only the hash state: nothing it keeps grows with the file.
 */
internal class ContentDigest(private val algorithm: DigestAlgorithm) {

    private val digest = when (algorithm) {
        DigestAlgorithm.SHA256 -> SHA256()
    }

    /**
     * How many bytes have gone through it.
     *
     * A hash cannot be asked what it has seen, and the answer decides whether an interrupted
     * transfer has to read its own partial file back before resuming. Compared against the
     * length of that file, this says whether the two describe the same bytes.
     */
    var consumedBytes: Long = 0L
        private set

    fun update(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        digest.update(bytes, offset, length)
        consumedBytes += length.toLong()
    }

    /** Consumes the accumulated state and returns the digest. */
    fun finish(): Checksum = Checksum(algorithm, digest.digest().toHexString())
}

/**
 * Feeds every remaining byte of this source into [digest], reusing [buffer].
 *
 * The buffer is the caller's and is reused for every read, so the memory this costs is the
 * buffer and nothing else — the file never accumulates anywhere.
 */
internal fun Source.readFullyInto(buffer: ByteArray, digest: ContentDigest) {
    while (true) {
        val read = readAtMostTo(buffer, 0, buffer.size)
        if (read <= 0) return
        digest.update(buffer, 0, read)
    }
}

/**
 * Lowercase hex, which is what [Checksum] carries.
 *
 * `ByteArray.toHexString()` from the standard library is still experimental, and the value
 * here is compared against digests published by servers — a representation that changed
 * under us would turn every comparison into a mismatch.
 */
internal fun ByteArray.toHexString(): String {
    val hex = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        hex.append(HEX_DIGITS[value ushr 4])
        hex.append(HEX_DIGITS[value and 0x0F])
    }
    return hex.toString()
}

private const val HEX_DIGITS = "0123456789abcdef"
