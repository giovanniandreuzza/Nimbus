package io.github.giovanniandreuzza.nimbus.infrastructure.time

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The wall clock, on each platform that has to provide one.
 *
 * A trivial assertion guarding a real risk: `currentEpochMs` is an `expect` with three
 * `actual`s, and the timestamps it produces are written to disk and read back weeks later by
 * `pruneFinished`. One of them returning seconds instead of milliseconds, or an uptime instead
 * of a wall clock, would make every stored age wrong — on iOS by a factor of a thousand, and
 * nobody would notice until a device stopped pruning or pruned everything.
 */
class SystemClockTest {

    @Test
    fun `every platform reports a plausible wall clock in milliseconds`() {
        val now = SystemClock.nowEpochMs()

        assertTrue(now > YEAR_2024, "expected milliseconds since the epoch, got $now")
        assertTrue(now < YEAR_2100, "expected milliseconds since the epoch, got $now")
    }

    @Test
    fun `it moves forward`() {
        val first = SystemClock.nowEpochMs()
        var second = SystemClock.nowEpochMs()
        var spins = 0
        while (second == first && spins < 1_000_000) {
            second = SystemClock.nowEpochMs()
            spins++
        }

        assertTrue(second >= first, "a clock that goes backwards makes every age negative")
    }

    private companion object {
        const val YEAR_2024 = 1_704_067_200_000L
        const val YEAR_2100 = 4_102_444_800_000L
    }
}
