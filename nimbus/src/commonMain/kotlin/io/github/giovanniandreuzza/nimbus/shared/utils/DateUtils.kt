package io.github.giovanniandreuzza.nimbus.shared.utils

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Date Utils.
 *
 * @author Giovanni Andreuzza
 */
public object DateUtils {

    /**
     * Get the current moment.
     *
     * @return The current moment.
     */
    public fun now(): LocalDateTime {
        val currentMoment: Instant = Clock.System.now()
        return currentMoment.toLocalDateTime(TimeZone.UTC)
    }
}