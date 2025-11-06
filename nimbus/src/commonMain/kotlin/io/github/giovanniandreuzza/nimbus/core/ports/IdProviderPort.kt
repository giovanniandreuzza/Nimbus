package io.github.giovanniandreuzza.nimbus.core.ports

import io.github.giovanniandreuzza.explicitarchitecture.core.application.ports.IsPort

/**
 * Id Provider Port.
 *
 * @author Giovanni Andreuzza
 */
@IsPort
internal interface IdProviderPort {

    /**
     * Generate an unique ID from a string.
     *
     * @param value The string to generate the unique ID from.
     * @return The generated unique ID.
     */
    fun generateUniqueId(value: String): String
}