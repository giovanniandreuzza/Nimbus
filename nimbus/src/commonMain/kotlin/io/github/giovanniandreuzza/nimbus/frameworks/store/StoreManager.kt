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

@ExperimentalSerializationApi
internal abstract class StoreManager<T>(
    private val filePath: String,
    private val nimbusStoragePort: NimbusStoragePort,
    private val serializer: KSerializer<T>,
    private val dispatcher: CoroutineDispatcher,
) {
    private val protoBuf: ProtoBuf = ProtoBuf
    private val mutex = Mutex()

    internal var data: T? = null

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
                    CreateStoreError.StoreAlreadyExists -> return load()
                    is CreateStoreError.IOError -> InitStoreError.IOError(cause)
                    is CreateStoreError.ReadPermissionDenied -> InitStoreError.ReadPermissionDenied(
                        cause
                    )

                    is CreateStoreError.WritePermissionDenied -> InitStoreError.WritePermissionDenied(
                        cause
                    )
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
    @OptIn(InternalIoApi::class)
    suspend fun store(data: T): KResult<Unit, StoreError> {
        return mutex.withLock {
            val sink = nimbusStoragePort.sink(path = filePath, hasToAppend = false).getOr {
                with(it) {
                    val error = when (this) {
                        GetFileSinkError.FileNotFound -> StoreError.StoreNotFound
                        is GetFileSinkError.ReadPermissionDenied -> StoreError.ReadPermissionDenied(
                            cause
                        )

                        is GetFileSinkError.WritePermissionDenied -> StoreError.WritePermissionDenied(
                            cause
                        )
                    }
                    return@withLock Failure(error)
                }
            }

            try {
                withContext(dispatcher) {
                    val encodedData = protoBuf.encodeToByteArray(serializer, data)
                    sink.use { sink ->
                        sink.write(encodedData)
                        sink.flush()
                    }
                    Success(Unit)
                }
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
            val source = nimbusStoragePort.source(path = filePath).getOr {
                with(it) {
                    val error = when (this) {
                        GetFileSourceError.FileNotFound -> ReadError.StoreNotFound
                        is GetFileSourceError.ReadPermissionDenied -> ReadError.ReadPermissionDenied(
                            it
                        )
                    }
                    return@withLock Failure(error)
                }
            }

            try {
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
                }
            }
            Success(Unit)
        }
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