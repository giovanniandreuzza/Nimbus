package io.github.giovanniandreuzza.nimbus.frameworks.store

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.getOr
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onFailure
import io.github.giovanniandreuzza.nimbus.frameworks.store.errors.CreateStoreError
import io.github.giovanniandreuzza.nimbus.frameworks.store.errors.DeleteStoreError
import io.github.giovanniandreuzza.nimbus.frameworks.store.errors.DoesStoreExistError
import io.github.giovanniandreuzza.nimbus.frameworks.store.errors.InitStoreError
import io.github.giovanniandreuzza.nimbus.frameworks.store.errors.ReadError
import io.github.giovanniandreuzza.nimbus.frameworks.store.errors.StoreError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.CreateFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.DeleteFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSinkError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSourceError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.MoveFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.storage.NimbusStoragePort
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.InternalIoApi
import kotlinx.io.readByteArray
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.coroutines.cancellation.CancellationException

@ExperimentalSerializationApi
internal abstract class StoreManager<T>(
    private val filePath: String,
    private val nimbusStoragePort: NimbusStoragePort,
    private val serializer: KSerializer<T>,
    private val dispatcher: CoroutineDispatcher,
    private val onReset: (suspend (reason: String) -> Unit)? = null,
) {
    /**
     * Default configuration, which omits any value equal to its declared default.
     *
     * That has a consequence worth knowing when adding a field: give it a default the code
     * never actually writes, or it will not reach the disk. `DownloadStore.schemaVersion`
     * is the cautionary case — it defaulted to the current version, so it was never
     * encoded, and a store from an older build decoded as whatever the reading build's
     * default happened to be. It now defaults to a value no build writes.
     *
     * `encodeDefaults = true` is not the fix: ProtoBuf has no representation for an absent
     * optional field's null, so turning it on fails to encode every nullable field.
     */
    private val protoBuf: ProtoBuf = ProtoBuf
    private val mutex = Mutex()
    private val tempFilePath: String = "$filePath.tmp"

    /**
     * The value currently held by the store. Subclasses may read it; deriving a new value
     * from it and persisting that must go through [update], which performs the
     * read-modify-write atomically.
     */
    protected var data: T? = null
        private set

    fun isReady(): Boolean = data != null

    /**
     * Initializes the store with a default value.
     *
     * @param initValue the default value to initialize the store with
     * @return [Success] if the store was initialized successfully, or already exists,
     * or [Failure] with:
     * - [InitStoreError.IOError] if an IO error occurs;
     * - [InitStoreError.ReadPermissionDenied] if a read permission error occurs;
     * - [InitStoreError.WritePermissionDenied] if a write permission error occurs;
     * - [InitStoreError.SerializationError] if a serialization error occurs;
     * - [InitStoreError.DeserializationError] if a deserialization error occurs;
     * - [InitStoreError.StoreNotFound] if the store has been deleted during the initialization;
     * - [InitStoreError.StoreFailed] if the store fails;
     */
    suspend fun init(initValue: T): KResult<Unit, InitStoreError> {
        if (isReady()) {
            return Success(Unit)
        }
        create().onFailure {
            with(it) {
                val error = when (this) {
                    CreateStoreError.StoreAlreadyExists -> return loadOrReset(initValue)
                    is CreateStoreError.IOError -> InitStoreError.IOError(cause)
                    is CreateStoreError.ReadPermissionDenied -> InitStoreError.ReadPermissionDenied(
                        cause
                    )

                    is CreateStoreError.WritePermissionDenied -> InitStoreError.WritePermissionDenied(
                        cause
                    )

                    is CreateStoreError.UnexpectedError -> InitStoreError.StoreFailed(cause)
                }
                return Failure(error)
            }
        }
        storeDefault(initValue)
        return Success(Unit)
    }

    /**
     * Checks if the storage file exists.
     *
     * @return [Success] containing true if the file exists, false otherwise,
     * or [Failure] with:
     * - [DoesStoreExistError.ReadPermissionDenied] if the file doesn't exist.
     */
    fun exists(): KResult<Boolean, DoesStoreExistError> {
        val result = nimbusStoragePort.exists(filePath).getOr {
            return Failure(DoesStoreExistError.ReadPermissionDenied(it))
        }
        return Success(result)
    }

    /**
     * Stores data to the file in a thread-safe manner.
     *
     * @param data the data to store
     * @return [Success] containing the read data, or [Failure] with:
     * - [StoreError.StoreNotFound] if the store doesn't exist;
     * - [StoreError.StoreFailed] if the store fails;
     * - [StoreError.ReadPermissionDenied] if a read permission error occurs;
     * - [StoreError.WritePermissionDenied] if a write permission error occurs;
     * - [StoreError.SerializationError] if a serialization error occurs;
     * - [StoreError.IOError] if an IO error occurs.
     */
    suspend fun store(data: T): KResult<Unit, StoreError> {
        return mutex.withLock { writeLocked(data) }
    }

    /**
     * Atomically reads the current value, applies [transform], publishes the result and
     * persists it — all under the store lock.
     *
     * [store] alone cannot offer this: a caller that reads [data], derives a new value and
     * calls [store] performs a read-modify-write with no lock held, so two concurrent callers
     * can both derive from the same base and the later write silently drops the earlier one.
     * Callers that mutate a value derived from the current one must use this instead.
     *
     * No-op returning [Success] when the store has not been initialised yet.
     */
    suspend fun update(transform: (T) -> T): KResult<Unit, StoreError> {
        return mutex.withLock {
            val current = data ?: return@withLock Success(Unit)
            val updated = transform(current)
            data = updated
            writeLocked(updated)
        }
    }

    /**
     * Applies [transform] to the current value and publishes the result **without writing
     * it to disk**.
     *
     * The pair of [mutate] and [flush] is [update] split in two, for callers that would
     * otherwise commit the whole store once per change. Because a commit rewrites
     * everything the store holds, a burst of changes is far cheaper as one commit of the
     * final value than as one commit each — but only a caller knows which of its changes
     * can wait for the next commit and which must be durable before it returns. Those that
     * must be durable use [update].
     */
    suspend fun mutate(transform: (T) -> T) {
        mutex.withLock {
            val current = data ?: return@withLock
            data = transform(current)
        }
    }

    /**
     * Commits whatever the store currently holds, including every change published by
     * [mutate] since the last commit.
     */
    suspend fun flush(): KResult<Unit, StoreError> {
        return mutex.withLock {
            val current = data ?: return@withLock Success(Unit)
            writeLocked(current)
        }
    }

    /**
     * Serialises [data] and commits it with a two-phase write.
     *
     * Callers must already hold [mutex] — [Mutex] is not reentrant, so this must never
     * acquire it itself.
     */
    @OptIn(InternalIoApi::class)
    private suspend fun writeLocked(data: T): KResult<Unit, StoreError> {
        return try {
            withContext(dispatcher) {
                val encodedData = protoBuf.encodeToByteArray(serializer, data)
                // Strict two-phase write: commit happens with atomic move.
                writeEncodedDataToPath(tempFilePath, encodedData).getOr {
                    return@withContext Failure(it)
                }
                nimbusStoragePort.atomicMove(tempFilePath, filePath).getOr {
                    return@withContext Failure(it.toStoreError())
                }
                Success(Unit)
            }
        } catch (e: CancellationException) {
            // Must be re-thrown — CancellationException is a subclass of
            // IllegalStateException on the JVM, so without this guard it would
            // be caught below and silently converted to a StoreFailed failure,
            // preventing coroutine cancellation from propagating correctly.
            throw e
        } catch (e: SerializationException) {
            val error = KError(
                code = "SerializationException",
                message = e.message ?: "Unknown serialization error"
            )
            Failure(StoreError.SerializationError(error))
        } catch (e: IOException) {
            val error = KError(
                code = "IOException",
                message = e.message ?: "Unknown IO error"
            )
            Failure(StoreError.IOError(error))
        } catch (e: IndexOutOfBoundsException) {
            val error = KError(
                code = "IndexOutOfBoundsException",
                message = e.message ?: "Index out of bounds error"
            )
            Failure(StoreError.StoreFailed(error))
        } catch (e: IllegalArgumentException) {
            val error = KError(
                code = "IllegalArgumentException",
                message = e.message ?: "Illegal argument error"
            )
            Failure(StoreError.StoreFailed(error))
        } catch (e: IllegalStateException) {
            val error = KError(
                code = "IllegalStateException",
                message = e.message ?: "Illegal state error"
            )
            Failure(StoreError.StoreFailed(error))
        }
    }

    /**
     * Reads data from the file in a thread-safe manner.
     *
     * @return [Success] containing the read data, or [Failure] with:
     * - [ReadError.StoreNotFound] if the store doesn't exist;
     * - [ReadError.ReadPermissionDenied] if a read permission error occurs;
     * - [ReadError.DeserializationError] if a deserialization error occurs;
     * - [ReadError.IOError] if an IO error occurs.
     */
    @OptIn(InternalIoApi::class)
    suspend fun read(): KResult<T, ReadError> {
        return mutex.withLock {
            val primary = readFromPath(filePath)
            if (primary is Success) {
                return@withLock primary
            }

            // Recovery fallback for interrupted writes.
            val fallback = readFromPath(tempFilePath)
            if (fallback is Success) {
                return@withLock fallback
            }

            primary
        }
    }

    /**
     * Deletes the storage file in a thread-safe manner.
     *
     * @return [Success] containing true if the file was deleted, false otherwise,
     * or [Failure] with:
     * - [DeleteStoreError.StoreNotFound] if the store doesn't exist;
     * - [DeleteStoreError.StoreDeletionFailed] if the store deletion fails;
     * - [DeleteStoreError.IOError] if an IO error occurs;
     * - [DeleteStoreError.ReadPermissionDenied] if a read permission error occurs;
     * - [DeleteStoreError.DeletePermissionDenied] if a delete permission error occurs.
     */
    suspend fun delete(): KResult<Unit, DeleteStoreError> {
        return mutex.withLock {
            nimbusStoragePort.delete(filePath).onFailure {
                with(it) {
                    val error = when (this) {
                        DeleteFileError.FileNotFound -> DeleteStoreError.StoreNotFound
                        DeleteFileError.DeleteFailed -> DeleteStoreError.StoreDeletionFailed
                        is DeleteFileError.IOError -> DeleteStoreError.IOError(it)
                        is DeleteFileError.ReadPermissionDenied -> DeleteStoreError.ReadPermissionDenied(
                            it
                        )

                        is DeleteFileError.DeletePermissionDenied -> DeleteStoreError.DeletePermissionDenied(
                            it
                        )

                        is DeleteFileError.UnexpectedError -> DeleteStoreError.UnexpectedError(it)
                    }
                    return@withLock Failure(error)
                }
            }
            Success(Unit)
        }
    }

    /* PRIVATE METHODS */

    /**
     * Creates the storage file if it doesn't exist.
     *
     * @return [Success] if the store was created successfully, or already exists,
     * or [Failure] with:
     * - [CreateStoreError.StoreAlreadyExists] if the file already exists;
     * - [CreateStoreError.IOError] if an IO error occurs;
     * - [CreateStoreError.ReadPermissionDenied] if a read permission error occurs.
     * - [CreateStoreError.WritePermissionDenied] if a write permission error occurs.
     */
    private suspend fun create(): KResult<Unit, CreateStoreError> {
        return mutex.withLock {
            nimbusStoragePort.create(filePath).onFailure { error ->
                return@withLock when (error) {
                    is CreateFileError.FileAlreadyExists -> Failure(CreateStoreError.StoreAlreadyExists)
                    is CreateFileError.IOError -> Failure(CreateStoreError.IOError(error.cause))
                    is CreateFileError.ReadPermissionDenied ->
                        Failure(CreateStoreError.ReadPermissionDenied(error.cause))

                    is CreateFileError.WritePermissionDenied ->
                        Failure(CreateStoreError.WritePermissionDenied(error.cause))

                    is CreateFileError.UnexpectedError ->
                        Failure(CreateStoreError.UnexpectedError(error.cause))
                }
            }
            Success(Unit)
        }
    }

    @OptIn(InternalIoApi::class)
    private suspend fun writeEncodedDataToPath(
        path: String,
        encodedData: ByteArray
    ): KResult<Unit, StoreError> {
        val exists = nimbusStoragePort.exists(path).getOr {
            return Failure(StoreError.ReadPermissionDenied(it))
        }

        if (!exists) {
            nimbusStoragePort.create(path).onFailure { createError ->
                return when (createError) {
                    CreateFileError.FileAlreadyExists -> Success(Unit)
                    is CreateFileError.IOError -> Failure(StoreError.IOError(createError.cause))
                    is CreateFileError.ReadPermissionDenied ->
                        Failure(StoreError.ReadPermissionDenied(createError.cause))

                    is CreateFileError.WritePermissionDenied ->
                        Failure(StoreError.WritePermissionDenied(createError.cause))

                    is CreateFileError.UnexpectedError ->
                        Failure(StoreError.StoreFailed(createError.cause))
                }
            }
        }

        val sink = nimbusStoragePort.sink(path = path, hasToAppend = false).getOr {
            with(it) {
                val error = when (this) {
                    GetFileSinkError.FileNotFound -> StoreError.StoreNotFound
                    is GetFileSinkError.ReadPermissionDenied -> StoreError.ReadPermissionDenied(
                        cause
                    )

                    is GetFileSinkError.WritePermissionDenied -> StoreError.WritePermissionDenied(
                        cause
                    )

                    is GetFileSinkError.UnexpectedError -> StoreError.StoreFailed(cause)
                }
                return Failure(error)
            }
        }

        return try {
            sink.use { out ->
                out.write(encodedData)
                out.flush()
            }
            Success(Unit)
        } catch (e: IOException) {
            val error = KError(
                code = "IOException",
                message = e.message ?: "Unknown IO error"
            )
            Failure(StoreError.IOError(error))
        }
    }

    @OptIn(InternalIoApi::class)
    private suspend fun readFromPath(path: String): KResult<T, ReadError> {
        val source = nimbusStoragePort.source(path = path).getOr {
            with(it) {
                val error = when (this) {
                    GetFileSourceError.FileNotFound -> ReadError.StoreNotFound
                    is GetFileSourceError.ReadPermissionDenied -> ReadError.ReadPermissionDenied(it)
                    is GetFileSourceError.UnexpectedError -> ReadError.UnexpectedError(it)
                }
                return Failure(error)
            }
        }

        return try {
            withContext(dispatcher) {
                val decodedData = source.use { source ->
                    val encodedData = source.readByteArray()
                    protoBuf.decodeFromByteArray(serializer, encodedData)
                }
                Success(decodedData)
            }
        } catch (e: SerializationException) {
            val error = KError(
                code = "SerializationException",
                message = e.message ?: "Unknown deserialization error"
            )
            Failure(ReadError.DeserializationError(error))
        } catch (e: IllegalArgumentException) {
            val error = KError(
                code = "IllegalArgumentException",
                message = e.message ?: "Illegal argument error"
            )
            Failure(ReadError.DeserializationError(error))
        } catch (e: IOException) {
            val error = KError(
                code = "IOException",
                message = e.message ?: "Unknown IO error"
            )
            Failure(ReadError.IOError(error))
        }
    }

    private fun MoveFileError.toStoreError(): StoreError = when (this) {
        MoveFileError.FileNotFound -> StoreError.StoreNotFound
        MoveFileError.MoveFailed -> StoreError.StoreFailed(
            KError(code = "move_failed", message = message)
        )

        is MoveFileError.IOError -> StoreError.IOError(cause)
        is MoveFileError.ReadPermissionDenied -> StoreError.ReadPermissionDenied(cause)
        is MoveFileError.WritePermissionDenied -> StoreError.WritePermissionDenied(cause)
        is MoveFileError.UnexpectedError -> StoreError.StoreFailed(cause)
    }

    /**
     * Loads an existing store, discarding it when it cannot be decoded.
     *
     * The store is a cache, not a source of truth: a blob written by an older,
     * incompatible schema must not brick every later call. On a deserialization
     * error the file (and any interrupted-write temp file) is dropped and the
     * store restarts from [initValue].
     *
     * @param initValue the default value to restart from when the store is unreadable
     */
    private suspend fun loadOrReset(initValue: T): KResult<Unit, InitStoreError> {
        load().onFailure { error ->
            if (error !is InitStoreError.DeserializationError) {
                return Failure(error)
            }

            return reset(initValue, "store could not be decoded: ${error.cause.message}")
        }

        return Success(Unit)
    }

    /**
     * Drops the store (and any interrupted-write temp file) and restarts from
     * [initValue].
     *
     * @param initValue the default value to restart from
     * @param reason why the store was discarded, reported through [onReset]
     */
    protected suspend fun reset(initValue: T, reason: String): KResult<Unit, InitStoreError> {
        nimbusStoragePort.delete(filePath)
        nimbusStoragePort.delete(tempFilePath)

        onReset?.invoke(reason)

        return storeDefault(initValue)
    }

    /**
     * Read the data from the store and initialize the data property.
     *
     * @return [Success] if the store was read successfully and the data initialized property,
     * or [Failure] with:
     * - [InitStoreError.IOError] if an IO error occurs;
     * - [InitStoreError.ReadPermissionDenied] if a read permission error occurs;
     * - [InitStoreError.StoreNotFound] if the store has been deleted during the initialization;
     * - [InitStoreError.DeserializationError] if a deserialization error occurs;
     */
    private suspend fun load(): KResult<Unit, InitStoreError> {
        data = read().getOr {
            with(it) {
                val error = when (this) {
                    is ReadError.DeserializationError -> InitStoreError.DeserializationError(cause)
                    is ReadError.IOError -> InitStoreError.IOError(cause)
                    is ReadError.ReadPermissionDenied -> InitStoreError.ReadPermissionDenied(cause)
                    ReadError.StoreNotFound -> InitStoreError.StoreNotFound
                    is ReadError.UnexpectedError -> InitStoreError.StoreFailed(cause)
                }
                return Failure(error)
            }
        }
        return Success(Unit)
    }

    /**
     * Stores the default value in the store and initializes the data property.
     *
     * @param data the default value to store
     * @return [Success] if the store was stored successfully and the data property initialized,
     * or [Failure] with:
     * - [InitStoreError.IOError] if an IO error occurs;
     * - [InitStoreError.ReadPermissionDenied] if a read permission error occurs;
     * - [InitStoreError.StoreNotFound] if the store has been deleted during the initialization;
     * - [InitStoreError.SerializationError] if a serialization error occurs;
     * - [InitStoreError.StoreFailed] if the store fails
     * - [InitStoreError.WritePermissionDenied] if a write permission error occurs.
     */
    private suspend fun storeDefault(data: T): KResult<Unit, InitStoreError> {
        store(data).onFailure {
            with(it) {
                val error = when (this) {
                    is StoreError.IOError -> InitStoreError.IOError(cause)
                    is StoreError.ReadPermissionDenied -> InitStoreError.ReadPermissionDenied(cause)
                    is StoreError.SerializationError -> InitStoreError.SerializationError(cause)
                    is StoreError.StoreFailed -> InitStoreError.StoreFailed(cause)
                    StoreError.StoreNotFound -> InitStoreError.StoreNotFound
                    is StoreError.WritePermissionDenied -> InitStoreError.WritePermissionDenied(
                        cause
                    )
                }
                return Failure(error)
            }
        }
        this.data = data
        return Success(Unit)
    }
}