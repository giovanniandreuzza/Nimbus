package io.github.giovanniandreuzza.nimbus.shared.ddd.events

/**
 * Event Handler.
 *
 * @author Giovanni Andreuzza
 */
public interface EventHandler<T : Event> {
    public fun handle(event: T)
}