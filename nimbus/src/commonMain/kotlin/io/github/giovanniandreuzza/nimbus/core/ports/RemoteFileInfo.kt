package io.github.giovanniandreuzza.nimbus.core.ports

/**
 * What the transport can say about a remote file before transferring it.
 *
 * @param sizeBytes how long the file is.
 * @param validator an opaque token identifying *this version* of it, or null when the
 * transport has none. Handed back on a resume so the origin can refuse to append the tail of a
 * file that is no longer the one the prefix came from.
 * @author Giovanni Andreuzza
 */
internal data class RemoteFileInfo(
    val sizeBytes: Long,
    val validator: String?
)
