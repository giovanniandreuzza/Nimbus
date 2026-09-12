package io.github.giovanniandreuzza.nimbus.shared.utils

import io.github.giovanniandreuzza.nimbus.presentation.RetryPolicy
import io.github.giovanniandreuzza.nimbus.testing.MidJitter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The arithmetic both retry loops run on.
 *
 * Three properties matter and each one was a defect before this existed: the wait has to
 * *grow*, or a link that takes ten seconds to come back is given three; it has to *stop*
 * growing, or an unattended device that has been failing all week waits days between tries;
 * and it has to be *spread*, or a fleet that lost the same backend comes back in lockstep and
 * takes turns knocking it over.
 */
class RetryDelayTest {

    @Test
    fun `the wait doubles per attempt`() {
        val policy = RetryPolicy(maxAttempts = null, baseDelayMs = 500L, maxDelayMs = 60_000L)

        val waits = (1..5).map { policy.delayForAttempt(it, MidJitter) }

        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L), waits)
    }

    @Test
    fun `the ceiling holds whatever the attempt number`() {
        val policy = RetryPolicy(maxAttempts = null, baseDelayMs = 2_000L, maxDelayMs = 300_000L)

        // 2 s doubling reaches the ceiling at the eighth wait (2·2^8 = 512 s).
        assertEquals(256_000L, policy.delayForAttempt(8, MidJitter), "still under the cap")
        assertEquals(300_000L, policy.delayForAttempt(9, MidJitter))
        assertEquals(300_000L, policy.delayForAttempt(50, MidJitter))
        assertEquals(
            300_000L,
            policy.delayForAttempt(10_000, MidJitter),
            "an unbounded loop on a device that has been up for months reaches numbers where " +
                    "a naive shift overflows a Long and starts asking for negative waits"
        )
    }

    @Test
    fun `every wait is spread around its nominal value and never reaches zero`() {
        val policy = RetryPolicy(maxAttempts = null, baseDelayMs = 1_000L, maxDelayMs = 60_000L)
        val random = Random(seed = 20260913)

        val waits = (1..200).map { policy.delayForAttempt(1, random) }

        assertTrue(
            waits.all { it in 800L..1_200L },
            "±20 % of a second is 800..1200 ms, saw ${waits.min()}..${waits.max()}"
        )
        assertTrue(
            waits.distinct().size > 50,
            "identical waits across a fleet are the thing the spread exists to prevent"
        )
        assertTrue(
            waits.all { it > 0L },
            "a wait of zero is the bug this replaces, not a small version of it"
        )
    }

    @Test
    fun `a bounded policy stops allowing attempts past its count`() {
        val policy = RetryPolicy(maxAttempts = 3, baseDelayMs = 500L, maxDelayMs = 60_000L)

        assertTrue(policy.allowsAttempt(1))
        assertTrue(policy.allowsAttempt(3))
        assertFalse(policy.allowsAttempt(4))
    }

    @Test
    fun `an unbounded policy always allows another attempt`() {
        val policy = RetryPolicy(maxAttempts = null, baseDelayMs = 500L, maxDelayMs = 60_000L)

        assertTrue(policy.allowsAttempt(1))
        assertTrue(
            policy.allowsAttempt(100_000),
            "the default for auto-retry: on a kiosk, giving up is a technician's visit"
        )
    }

    @Test
    fun `a policy that cannot be honoured is refused where it is written`() {
        // Caught in the constructor rather than at the first retry, which on an unattended
        // device would be hours after the build that got it wrong.
        assertFails { RetryPolicy(maxAttempts = -1, baseDelayMs = 500L, maxDelayMs = 1_000L) }
        assertFails { RetryPolicy(maxAttempts = 3, baseDelayMs = 0L, maxDelayMs = 1_000L) }
        assertFails { RetryPolicy(maxAttempts = 3, baseDelayMs = 5_000L, maxDelayMs = 1_000L) }
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected the policy to be refused")
        } catch (_: IllegalArgumentException) {
            // what we wanted
        }
    }
}
