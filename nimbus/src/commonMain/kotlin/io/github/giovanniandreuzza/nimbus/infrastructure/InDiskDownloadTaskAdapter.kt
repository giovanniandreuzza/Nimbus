package io.github.giovanniandreuzza.nimbus.infrastructure

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.asSuccess
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isFailure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.toResultOrFailure
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.application.errors.FailedToLoadDownloadTasks
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.frameworks.filemanager.NimbusFileManager
import io.github.giovanniandreuzza.nimbus.frameworks.filemanager.models.DownloadStore
import io.github.giovanniandreuzza.nimbus.infrastructure.mappers.DownloadTaskStoreMappers.toDTO
import io.github.giovanniandreuzza.nimbus.infrastructure.mappers.DownloadTaskStoreMappers.toStore
import io.github.giovanniandreuzza.nimbus.shared.utils.getDownloadProgress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Sink
import okio.Source
import okio.buffer
import okio.use

/**
 * In Disk Download Task Adapter.
 *
 * @param ioDispatcher The IO dispatcher.
 * @param downloadManagerPath The download manager path.
 * @param fileManager The nimbus file manager.
 * @author Giovanni Andreuzza
 */
internal class InDiskDownloadTaskAdapter(
    private val ioDispatcher: CoroutineDispatcher,
    private val downloadManagerPath: String,
    private val fileManager: NimbusFileManager,
) : DownloadTaskRepository {

    private val mutex = Mutex()

    override suspend fun loadDownloadTasks(): KResult<Unit, FailedToLoadDownloadTasks> {
        val sourceResult = fileManager.getFileSource(downloadManagerPath)

        if (sourceResult.isFailure()) {
            val result = fileManager.createFile(downloadManagerPath)

            if (result.isFailure()) {
                return Failure(FailedToLoadDownloadTasks(result.error.toString()))
            }
        }

        getAllDownloadTask().values.forEach {
            if (it.state is DownloadState.Downloading) {
                val updatedDownloadTask = it.copy(
                    state = DownloadState.Paused(
                        progress = it.state.progress
                    ),
                    version = it.version + 1
                )

                saveDownloadTask(updatedDownloadTask)
            }
        }

        return Success(Unit)
    }

    override suspend fun getDownloadTask(
        id: String
    ): KResult<DownloadTaskDTO, DownloadTaskNotFound> {
        return mutex.withLock {
            val sourceResult = fileManager.getFileSource(downloadManagerPath)

            if (sourceResult.isFailure()) {
                return@withLock Failure(DownloadTaskNotFound(sourceResult.error.toString()))
            }

            val downloadStore = readDownloadStoreFromFile(sourceResult.value)

            val downloadTask = downloadStore.downloads[id]?.toDTO()

            val syncDownloadTask = downloadTask?.let {
                syncWithFile(downloadTask, downloadStore)
            }

            syncDownloadTask.toResultOrFailure { DownloadTaskNotFound(id) }
        }
    }

    override suspend fun getAllDownloadTask(): Map<String, DownloadTaskDTO> {
        return mutex.withLock {
            val sourceResult = fileManager.getFileSource(downloadManagerPath)

            if (sourceResult.isFailure()) {
                return@withLock emptyMap()
            }

            val downloadStore = readDownloadStoreFromFile(sourceResult.value)

            downloadStore.downloads.mapValues { downloadTask ->
                downloadTask.value.toDTO().let {
                    syncWithFile(it, downloadStore)
                }
            }.filterValues { downloadTask ->
                downloadTask != null
            }.mapValues { entry ->
                entry.value!!
            }
        }
    }

    override fun observeDownloadState(
        id: String
    ): KResult<Flow<DownloadState>, DownloadTaskNotFound> {
        throw NotImplementedError()
    }

    override suspend fun saveDownloadTask(
        downloadTask: DownloadTaskDTO
    ): KResult<Unit, DownloadTaskNotFound> {
        return mutex.withLock {
            val sourceResult = fileManager.getFileSource(downloadManagerPath)

            if (sourceResult.isFailure()) {
                return@withLock Failure(DownloadTaskNotFound(sourceResult.error.toString()))
            }

            val downloadStore = readDownloadStoreFromFile(sourceResult.value)

            val storedVersion = downloadStore.downloads[downloadTask.id]?.version ?: -1

            if (storedVersion < downloadTask.version) {
                downloadStore.downloads[downloadTask.id] = downloadTask.toStore()

                val sink = fileManager.getFileSink(
                    filePath = downloadManagerPath,
                    hasToAppend = false
                ).asSuccess().value

                saveDownloadStoreToFile(downloadStore, sink)
            }

            Success(Unit)
        }
    }

    override fun saveDownloadProgress(downloadTask: DownloadTaskDTO): KResult<Unit, DownloadTaskNotFound> {
        throw NotImplementedError()
    }

    override suspend fun deleteDownloadTask(id: String): KResult<Boolean, DownloadTaskNotFound> {
        return mutex.withLock {
            val sourceResult = fileManager.getFileSource(downloadManagerPath)

            if (sourceResult.isFailure()) {
                return@withLock Failure(DownloadTaskNotFound(sourceResult.error.toString()))
            }

            val downloadStore = readDownloadStoreFromFile(sourceResult.value)

            if (!downloadStore.downloads.containsKey(id)) {
                return@withLock Failure(DownloadTaskNotFound())
            }

            val downloadTask = downloadStore.downloads[id]!!

            if (fileManager.exists(downloadTask.filePath)) {
                fileManager.deleteFile(downloadTask.filePath)
            }

            downloadStore.downloads.remove(downloadTask.id)

            val sink = fileManager.getFileSink(
                filePath = downloadManagerPath,
                hasToAppend = false
            ).asSuccess().value

            saveDownloadStoreToFile(downloadStore, sink)

            Success(true)
        }
    }

    /* Private Methods */

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun readDownloadStoreFromFile(source: Source): DownloadStore {
        return withContext(ioDispatcher) {
            try {
                source.buffer().use { source ->
                    val bytes = source.readByteArray()
                    ProtoBuf.decodeFromByteArray(bytes)
                }
            } catch (_: Exception) {
                DownloadStore()
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun saveDownloadStoreToFile(store: DownloadStore, sink: Sink) {
        return withContext(ioDispatcher) {
            val encodedData = ProtoBuf.encodeToByteArray(store)
            sink.buffer().use { sink ->
                sink.write(encodedData)
            }
        }
    }

    private suspend fun syncWithFile(
        downloadTask: DownloadTaskDTO,
        downloadStore: DownloadStore
    ): DownloadTaskDTO? {
        if (downloadTask.state is DownloadState.Enqueued) {
            return downloadTask
        }

        val fileSizeResult = fileManager.getFileSize(downloadTask.filePath)

        // Remove the download task from the store if the download file does not exist
        if (fileSizeResult.isFailure()) {
            val sink = fileManager.getFileSink(
                filePath = downloadManagerPath,
                hasToAppend = false
            ).asSuccess().value
            downloadStore.downloads.remove(downloadTask.id)
            saveDownloadStoreToFile(downloadStore, sink)
            return null
        }

        val bytesDownloaded = fileSizeResult.value

        // Update the download task progress with the actual bytes downloaded
        return downloadTask.copy(
            state = when (downloadTask.state) {
                is DownloadState.Downloading -> {
                    downloadTask.state.copy(
                        progress = getDownloadProgress(
                            bytesDownloaded,
                            downloadTask.fileSize
                        )
                    )
                }

                is DownloadState.Paused -> {
                    downloadTask.state.copy(
                        progress = getDownloadProgress(
                            bytesDownloaded,
                            downloadTask.fileSize
                        )
                    )
                }

                else -> downloadTask.state
            }
        )
    }
}