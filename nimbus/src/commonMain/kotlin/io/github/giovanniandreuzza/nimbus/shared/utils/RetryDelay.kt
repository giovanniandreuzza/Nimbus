package io.github.giovanniandreuzza.nimbus.shared.utils

import io.github.giovanniandreuzza.explicitarchitecture.shared.IsShared
import io.github.giovanniandreuzza.nimbus.presentation.RetryPolicy
import kotlin.random.Random

/**
 * How much of a wait is given up to chance. Fixed, not configurable: a value a caller could
 * set to zero would let a fleet retry in lockstep, which is the failure this exists to avoid.
 */
private const val JITTER_FRACTION = 0.2

/**
 * The wait before retry number [attempt], counting the first retry as 1.
 *
 * Doubles per attempt from [RetryPolicy.baseDelayMs] and stops at [RetryPolicy.maxDelayMs],
 * then spreads the result by ±20 % without ever exceeding that ceiling. The doubling is
 * computed in a way that cannot overflow: an unbounded auto-retry loop on a device that has
 * been up for months would otherwise shift a Long past its sign bit and start asking for
 * negative delays.
 *
 * @param random injectable so a test can assert the arithmetic rather than a range.
 */
@IsShared
internal fun RetryPolicy.delayForAttempt(
    attempt: Int,
    random: Random = Random.Default
): Long {
    if (attempt <= 0) return 0L

    val exponent = (attempt - 1).coerceAtMost(EXPONENT_CEILING)
    val doubled = baseDelayMs.toDouble() * (1L shl exponent).toDouble()
    val capped = doubled.coerceAtMost(maxDelayMs.toDouble())

    val spread = 1.0 - JITTER_FRACTION + random.nextDouble() * (2 * JITTER_FRACTION)

    // Clamped after the spread as well as before it. `maxDelayMs` is documented as the longest
    // wait, and +20 % of a five-minute ceiling is six minutes — a number the caller wrote a
    // limit specifically to rule out. At the ceiling the spread therefore only shortens, which
    // still separates a fleet.
    return (capped * spread).toLong().coerceIn(1L, maxDelayMs)
}

/**
 * Beyond this the cap has certainly been reached for any sane base delay, and shifting
 * further is how a Long becomes negative.
 */
private const val EXPONENT_CEILING = 32

/** Whether [attempt] is still within what the policy allows. */
@IsShared
internal fun RetryPolicy.allowsAttempt(attempt: Int): Boolean {
    val limit = maxAttempts ?: return true
    return attempt <= limit
}
