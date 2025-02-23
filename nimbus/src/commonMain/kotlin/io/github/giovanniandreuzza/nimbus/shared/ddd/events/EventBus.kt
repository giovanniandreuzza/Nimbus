package io.github.giovanniandreuzza.nimbus.shared.ddd.events

import kotlin.reflect.KClass

/**
 * Event Bus.
 *
 * @author Giovanni Andreuzza
 */
public interface EventBus<T : Event> {

    public fun publish(event: T)

    public fun publishAll(events: List<T>)

    public fun <E : Event> registerHandler(eventType: KClass<E>, handler: EventHandler<E>)

    public fun start()

    public fun stop()

}