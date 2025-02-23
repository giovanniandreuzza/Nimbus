package io.github.giovanniandreuzza.nimbus.shared.utils

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * KResult.
 *
 * @param T the success type
 * @param E the error type extending [KError]
 * @author Giovanni Andreuzza
 */
public sealed class KResult<out T, out E : KError> {

    override fun toString(): String {
        return when (this) {
            is Success -> "Success[value=$value]"
            is Failure -> "Failure[error=$error]"
        }
    }
}

/**
 * Success.
 *
 * @param value the success value
 * @return [KResult] with [value] as success value
 */
public class Success<T>(public val value: T) : KResult<T, Nothing>()

/**
 * Failure.
 *
 * @param error the error value
 * @return [KResult] with [error] as error value
 */
public class Failure<out E : KError>(public val error: E) : KResult<Nothing, E>()

@OptIn(ExperimentalContracts::class)
public fun <T, E : KError> KResult<T, E>.isSuccess(): Boolean {
    contract {
        returns(true) implies (this@isSuccess is Success)
        returns(false) implies (this@isSuccess is Failure)
    }
    return this is Success
}

/**
 * Force casts [KResult] to [Success].
 *
 * @throws ClassCastException if [KResult] is not [Success]
 */
public fun <T, E : KError> KResult<T, E>.asSuccess(): Success<T> {
    return this as Success
}

@OptIn(ExperimentalContracts::class)
public fun <T, E : KError> KResult<T, E>.isFailure(): Boolean {
    contract {
        returns(true) implies (this@isFailure is Failure)
        returns(false) implies (this@isFailure is Success)
    }
    return this is Failure
}

/**
 * Force casts [KResult] to [Failure].
 *
 * @throws ClassCastException if [KResult] is not [Failure]
 */
public fun <T, E : KError> KResult<T, E>.asFailure(): Failure<E> {
    return this as Failure
}

@OptIn(ExperimentalContracts::class)
public inline fun <T, E : KError> KResult<T, E>.onSuccess(action: (value: T) -> Unit): KResult<T, E> {
    contract {
        callsInPlace(action, InvocationKind.AT_MOST_ONCE)
    }
    if (this.isSuccess()) action(value)
    return this
}

@OptIn(ExperimentalContracts::class)
public inline fun <T, E : KError> KResult<T, E>.onFailure(action: (error: E) -> Unit): KResult<T, E> {
    contract {
        callsInPlace(action, InvocationKind.AT_MOST_ONCE)
    }
    if (this.isFailure()) action(error)
    return this
}

@OptIn(ExperimentalContracts::class)
public inline fun <T, E : KError> KResult<T, E>.fold(
    onSuccess: (T) -> Unit,
    onFailure: (E) -> Unit
): KResult<T, E> {
    contract {
        callsInPlace(onSuccess, InvocationKind.AT_MOST_ONCE)
        callsInPlace(onFailure, InvocationKind.AT_MOST_ONCE)
    }

    when (this) {
        is Success -> onSuccess(value)
        is Failure -> onFailure(error)
    }

    return this
}

public inline fun <T, reified E : KError> T.toResult(): KResult<T, E> {
    if (this is E) {
        return Failure(this)
    }
    return Success(this)
}

public inline fun <T, reified E : KError> T?.toResultOrSuccess(value: () -> T): KResult<T, E> {
    if (this == null) {
        return Success(value())
    }
    if (this is E) {
        return Failure(this)
    }
    return Success(this)
}

public inline fun <T, reified E : KError> T?.toResultOrFailure(error: () -> E): KResult<T, E> {
    if (this == null) {
        return Failure(error())
    }
    if (this is E) {
        return Failure(this)
    }
    return Success(this)
}
