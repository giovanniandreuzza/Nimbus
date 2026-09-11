package io.github.giovanniandreuzza.nimbus.core.ports

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm

/**
 * Computes the content digest of a file already on disk.
 *
 * Separate from [StoragePort] on purpose. Answering "what is the digest of this file"
 * requires reading every byte of it, which would mean putting `source` on the core-facing
 * storage surface — the one operation core has never needed and which would stop that
 * interface being core-shaped. Core asks the question; infrastructure, which already hashes
 * bytes as they stream past, answers it.
 *
 * @author Giovanni Andreuzza
 */
internal interface ContentDigestPort {

    /**
     * Reads the file at [path] in full and digests it with [algorithm].
     */
    suspend fun digestOf(
        path: String,
        algorithm: DigestAlgorithm
    ): KResult<Checksum, StoragePortError>
}
