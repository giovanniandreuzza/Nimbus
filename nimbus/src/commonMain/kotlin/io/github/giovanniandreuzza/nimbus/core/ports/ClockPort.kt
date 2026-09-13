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
 * Wall clock, not a monotonic one, because the question being asked is "when did this
 * finish" and only a wall clock survives a restart. The cost is that it can be wrong: a
 * device with no battery-backed clock comes up at the epoch, and NTP later moves it by
 * decades. `DownloadRepository` repairs stamps written before the clock was believable, and
 * `pruneFinished` refuses to read a future timestamp as an age.
 *
 * @author Giovanni Andreuzza
 */
@IsPort
internal fun interface ClockPort {
    fun nowEpochMs(): Long
}
