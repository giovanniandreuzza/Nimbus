package io.github.giovanniandreuzza.nimbus.shared.ddd.domain

import io.github.giovanniandreuzza.nimbus.shared.ddd.events.Event

/**
 * Domain Event.
 *
 * @author Giovanni Andreuzza
 */
public interface DomainEvent<ID> : Domain, Event {
    public val aggregateId: EntityId<ID>
    public val version: Int
}
