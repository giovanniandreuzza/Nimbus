package io.github.giovanniandreuzza.nimbus.infrastructure.ports

import io.github.giovanniandreuzza.explicitarchitecture.infrastructure.adapters.IsAdapter
import io.github.giovanniandreuzza.nimbus.core.ports.IdProviderPort
import org.kotlincrypto.hash.sha2.SHA256

/**
 * Id Provider Adapter.
 *
 * @author Giovanni Andreuzza
 */
@IsAdapter
internal class IdProviderAdapter : IdProviderPort {

    override fun generateUniqueId(value: String): String {
        return SHA256().digest(value.encodeToByteArray()).toHexString()
    }
}