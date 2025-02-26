package io.github.giovanniandreuzza.nimbus.frameworks.filemanager

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.IsFramework
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.frameworks.filemanager.errors.FileCreationFailed
import io.github.giovanniandreuzza.nimbus.frameworks.filemanager.errors.FileNotFound
import io.github.giovanniandreuzza.nimbus.frameworks.filemanager.models.DownloadStore
import io.github.giovanniandreuzza.nimbus.frameworks.filemanager.ports.NimbusFileRepository
import kotlinx.coroutines.CoroutineDispatcher
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
 * Nimbus File Manager.
 *
 * @param ioDispatcher Coroutine IO Dispatcher
 * @param nimbusFileRepository The Nimbus File Repository.
 * @author Giovanni Andreuzza
 */
@IsFramework
internal class NimbusFileManager(
    private val ioDispatcher: CoroutineDispatcher,
    private val nimbusFileRepository: NimbusFileRepository
) {

    fun exists(filePath: String): Boolean {
        return nimbusFileRepository.exists(filePath)
    }

    fun createFile(filePath: String): KResult<Unit, FileCreationFailed> {
        return nimbusFileRepository.createFile(filePath)
    }

    fun getFileSize(filePath: String): KResult<Long, FileNotFound> {
        return nimbusFileRepository.getFileSize(filePath)
    }

    fun getFileSink(filePath: String, hasToAppend: Boolean): KResult<Sink, FileNotFound> {
        return nimbusFileRepository.getFileSink(filePath, hasToAppend)
    }

    fun getFileSource(filePath: String): KResult<Source, FileNotFound> {
        return nimbusFileRepository.getFileSource(filePath)
    }

    fun deleteFile(filePath: String): KResult<Unit, FileNotFound> {
        return nimbusFileRepository.deleteFile(filePath)
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun readDownloadStoreFromFile(source: Source): DownloadStore {
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
    suspend fun saveDownloadStoreToFile(store: DownloadStore, sink: Sink) {
        return withContext(ioDispatcher) {
            val encodedData = ProtoBuf.encodeToByteArray(store)
            sink.buffer().use { sink ->
                sink.write(encodedData)
            }
        }
    }
}