package io.github.giovanniandreuzza.nimbus.infrastructure.time

import io.github.giovanniandreuzza.explicitarchitecture.infrastructure.adapters.IsAdapter
import io.github.giovanniandreuzza.nimbus.core.ports.ClockPort

/** Milliseconds since the epoch, from whatever the platform calls its wall clock. */
internal expect fun currentEpochMs(): Long

@IsAdapter
internal object SystemClock : ClockPort {
    override fun nowEpochMs(): Long = currentEpochMs()
}
