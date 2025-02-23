package io.github.giovanniandreuzza.nimbus.shared.ddd.application

import kotlinx.coroutines.flow.Flow

/**
 * Flow Use Case.
 *
 * @author Giovanni Andreuzza
 */
public abstract class FlowUseCase<out T : Application, in Params> : Application {

    protected abstract suspend fun buildUseCase(params: Params): Flow<T>

    /***
     * Execute asynchronously an api call.
     *
     * @params the parameters of the call
     * @return the response after launching the call
     */
    public suspend fun execute(params: Params): Flow<T> = buildUseCase(params)
}