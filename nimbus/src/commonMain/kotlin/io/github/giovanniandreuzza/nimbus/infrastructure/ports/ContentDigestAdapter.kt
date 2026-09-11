package io.github.giovanniandreuzza.nimbus.infrastructure.ports

import io.github.giovanniandreuzza.explicitarchitecture.infrastructure.adapters.IsAdapter
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.getOr
import io.github.giovanniandreuzza.nimbus.core.ports.ContentDigestPort
import io.github.giovanniandreuzza.nimbus.core.ports.StoragePortError
import io.github.giovanniandreuzza.nimbus.infrastructure.digest.ContentDigest
import io.github.giovanniandreuzza.nimbus.infrastructure.digest.readFullyInto
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.storage.NimbusStoragePort
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Digests a file by reading it back off storage.
 *
 * @param readBufferSize how much is read at a time. Bounds the memory this costs: nothing
 * here scales with the size of the file.
 * @author Giovanni Andreuzza
 */
@IsAdapter
internal class ContentDigestAdapter(
    private val nimbusStoragePort: NimbusStoragePort,
    private val dispatcher: CoroutineDispatcher,
    private val readBufferSize: Int = DEFAULT_READ_BUFFER_SIZE
) : ContentDigestPort {

    override suspend fun digestOf(
        path: String,
        algorithm: DigestAlgorithm
    ): KResult<Checksum, StoragePortError> {
        val source = nimbusStoragePort.source(path).getOr { return Failure(StoragePortError(it)) }

        return try {
            withContext(dispatcher) {
                val digest = ContentDigest(algorithm)
                source.use { it.readFullyInto(ByteArray(readBufferSize), digest) }
                Success(digest.finish())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Failure(
                StoragePortError(
                    KError(
                        code = "digest_read_failed",
                        message = t.message ?: "Could not read the file to digest it"
                    )
                )
            )
        }
    }

    private companion object {
        const val DEFAULT_READ_BUFFER_SIZE = 16 * 1024
    }
}
