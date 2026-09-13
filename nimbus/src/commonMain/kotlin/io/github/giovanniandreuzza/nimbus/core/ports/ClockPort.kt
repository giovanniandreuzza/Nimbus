package io.github.giovanniandreuzza.nimbus.core.ports

import io.github.giovanniandreuzza.explicitarchitecture.core.application.ports.IsPort

/**
 * Wall-clock time, in milliseconds since the epoch.
 *
 * A port rather than a call to the platform, for the usual reason and one specific to this
 * library: the timestamps it produces end up on disk and are read back weeks later by
 * `pruneFinished`, so a test that cannot control time cannot test pruning at all without
 * sleeping through it.
 *
 * @author Giovanni Andreuzza
 */
@IsPort
internal fun interface ClockPort {
    fun nowEpochMs(): Long
}
