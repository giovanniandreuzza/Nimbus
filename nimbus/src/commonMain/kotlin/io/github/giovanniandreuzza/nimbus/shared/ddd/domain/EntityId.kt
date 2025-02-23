package io.github.giovanniandreuzza.nimbus.shared.ddd.domain

/**
 * Entity Id.
 *
 * @author Giovanni Andreuzza
 */
public class EntityId<ID>(override val id: ID) : Identifier<ID>(id)