package io.github.giovanniandreuzza.nimbus.presentation

/**
 * How long to wait before trying again, and how many times.
 *
 * Two of these are in play, because the two retries answer different questions. The
 * **transport** policy covers a single download's own attempts: a dropped connection, a 5xx, a
 * link that went quiet. It is measured in seconds and the task never leaves `Downloading`.
 * The **auto-retry** policy covers what happens after those are spent and the task has
 * failed — the loop that exists so an unattended device eventually gets its file even when
 * the server was down for an hour. It is measured in minutes.
 *
 * The delay grows exponentially from [baseDelayMs], doubling per attempt, and stops growing at
 * [maxDelayMs]. Every wait is then spread by ±20 %, which is not configurable on purpose: a
 * fleet of devices that lost the same backend recovers together, and without that spread they
 * would come back in lockstep and take turns knocking the server over.
 *
 * @param maxAttempts how many retries to make after the first failure, or `null` to keep
 * trying for as long as the process lives. `null` is the default for [AutoRetry]: on a kiosk,
 * giving up permanently means a technician's visit, while waiting five minutes between tries
 * costs almost nothing.
 * @param baseDelayMs the first wait.
 * @param maxDelayMs the longest wait, whatever the attempt number.
 * @author Giovanni Andreuzza
 */
public data class RetryPolicy(
    public val maxAttempts: Int?,
    public val baseDelayMs: Long,
    public val maxDelayMs: Long
) {
    init {
        require(maxAttempts == null || maxAttempts >= 0) { "maxAttempts must be null or >= 0" }
        require(baseDelayMs > 0L) { "baseDelayMs must be > 0" }
        require(maxDelayMs >= baseDelayMs) { "maxDelayMs must be >= baseDelayMs" }
    }

    public companion object {
        /**
         * Retries within one download: five attempts from half a second up to a minute.
         *
         * Sized against the links these devices actually run on. An LTE reattach after a lost
         * signal takes ten to thirty seconds and a Wi-Fi reconnect with a DHCP round-trip five
         * to fifteen; a budget that expires in three seconds expires before the network has
         * finished coming back, which is how a transient failure became a failed task.
         */
        public val Transport: RetryPolicy = RetryPolicy(
            maxAttempts = 5,
            baseDelayMs = 500L,
            maxDelayMs = 60_000L
        )

        /**
         * Retries after a download has failed: from two seconds up to five minutes, forever.
         *
         * The ceiling is what matters. Until it existed this loop had no wait at all — a
         * backend in maintenance turned every failed task into a request flood, on a metered
         * link, with the whole fleet hammering in step.
         */
        public val AutoRetry: RetryPolicy = RetryPolicy(
            maxAttempts = null,
            baseDelayMs = 2_000L,
            maxDelayMs = 300_000L
        )
    }
}
