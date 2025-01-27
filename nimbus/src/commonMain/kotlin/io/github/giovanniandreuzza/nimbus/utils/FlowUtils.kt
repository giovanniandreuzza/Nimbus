package io.github.giovanniandreuzza.nimbus.utils

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.transformWhile

/**
 * Take elements from the flow until the predicate is true.
 *
 * @param predicate The predicate.
 * @return The new flow.
 */
public fun <T> Flow<T>.takeUntil(predicate: (T) -> Boolean): Flow<T> = transformWhile {
    emit(it)
    !predicate(it)
}

/**
 * Emit a value to the MutableSharedFlow contained in the map.
 *
 * @param key The key.
 * @param value The value.
 * @return True if the value was emitted, false otherwise.
 */
public fun <Key, Value> MutableMap<Key, MutableSharedFlow<Value>>.emit(
    key: Key,
    value: Value
): Boolean {
    return get(key)?.tryEmit(value) == true
}
