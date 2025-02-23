package io.github.giovanniandreuzza.nimbus.shared.ddd.events

import kotlinx.datetime.LocalDateTime

/**
 * Event.
 *
 * @author Giovanni Andreuzza
 */
public interface Event {
    public val occurredOn: LocalDateTime
}