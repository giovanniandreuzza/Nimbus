package io.github.giovanniandreuzza.nimbus.infrastructure

import io.github.giovanniandreuzza.explicitarchitecture.shared.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.asSuccess
import io.github.giovanniandreuzza.explicitarchitecture.shared.isFailure
import io.github.giovanniandreuzza.explicitarchitecture.shared.toResultOrFailure
import io.github.giovanniandreuzza.nimbus.api.NimbusFileRepository
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.infrastructure.mappers.toDTO
import io.github.giovanniandreuzza.nimbus.infrastructure.mappers.toStore
import io.github.giovanniandreuzza.nimbus.infrastructure.models.DownloadStore
import io.github.giovanniandreuzza.nimbus.shared.utils.getDownloadProgress
import kotlinx.coroutines.CoroutineDispatcher
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
 * Download Task Adapter.
 *
 * @param downloadManagerPath The download manager path.
 * @param nimbusFileRepository The file callback.
 * @author Giovanni Andreuzza
 */
internal class DownloadTaskAdapter(
    private val ioDispatcher: CoroutineDispatcher,
    private val downloadManagerPath: String,
    private val nimbusFileRepository: NimbusFileRepository
) : DownloadTaskRepository {

    private val mutex = Mutex()

    override suspend fun getDownloadTask(id: String): KResult<DownloadTaskDTO, DownloadTaskNotFound> {
        return mutex.withLock {
            val sourceResult = nimbusFileRepository.getFileSource(downloadManagerPath)

            if (sourceResult.isFailure()) {
                return@withLock Failure(DownloadTaskNotFound(id))
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
            val sourceResult = nimbusFileRepository.getFileSource(downloadManagerPath)

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

    override suspend fun saveDownloadTask(
        downloadTask: DownloadTaskDTO
    ): KResult<Unit, DownloadTaskNotFound> {
        return mutex.withLock {
            if (!nimbusFileRepository.exists(downloadManagerPath)) {
                val fileCreationResult = nimbusFileRepository.createFile(downloadManagerPath)

                if (fileCreationResult.isFailure()) {
                    return@withLock Failure(DownloadTaskNotFound(downloadTask.filePath))
                }
            }

            val source = nimbusFileRepository.getFileSource(downloadManagerPath).asSuccess().value
            val downloadStore = readDownloadStoreFromFile(source)

            val storedVersion = downloadStore.downloads[downloadTask.id]?.version ?: -1

            if (storedVersion < downloadTask.version) {
                downloadStore.downloads[downloadTask.id] = downloadTask.toStore()

                val sink = nimbusFileRepository.getFileSink(
                    filePath = downloadManagerPath,
                    hasToAppend = false
                ).asSuccess().value

                saveDownloadStoreToFile(downloadStore, sink)
            }

            Success(Unit)
        }
    }

    override suspend fun deleteDownloadTask(id: String): KResult<Boolean, DownloadTaskNotFound> {
        return mutex.withLock {
            if (!nimbusFileRepository.exists(downloadManagerPath)) {
                return@withLock Failure(DownloadTaskNotFound(id))
            }

            val source = nimbusFileRepository.getFileSource(downloadManagerPath).asSuccess().value

            val downloadStore = readDownloadStoreFromFile(source)

            if (!downloadStore.downloads.containsKey(id)) {
                return@withLock Failure(DownloadTaskNotFound(id))
            }

            val downloadTask = downloadStore.downloads[id]!!

            if (nimbusFileRepository.exists(downloadTask.filePath)) {
                nimbusFileRepository.deleteFile(downloadTask.filePath)
            }

            downloadStore.downloads.remove(downloadTask.id)

            val sink = nimbusFileRepository.getFileSink(
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

        val fileSizeResult = nimbusFileRepository.getFileSize(downloadTask.filePath)

        if (fileSizeResult.isFailure()) {
            val sink = nimbusFileRepository.getFileSink(
                filePath = downloadManagerPath,
                hasToAppend = false
            ).asSuccess().value
            downloadStore.downloads.remove(downloadTask.id)
            saveDownloadStoreToFile(downloadStore, sink)
            return null
        }

        val bytesDownloaded = fileSizeResult.value

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