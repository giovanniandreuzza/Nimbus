package io.github.giovanniandreuzza.nimbus.shared.ddd.application

import io.github.giovanniandreuzza.nimbus.shared.utils.KError
import io.github.giovanniandreuzza.nimbus.shared.utils.KResult

/**
 * Use Case.
 *
 * @author Giovanni Andreuzza
 */
public interface UseCase<in Params, T, out E : KError> : Application {

    /***
     * Execute synchronously an api call.
     *
     * @params the parameters of the call
     * @return the response after launching the call
     */
    public suspend fun execute(params: Params): KResult<T, E>
}