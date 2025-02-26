package io.github.giovanniandreuzza.nimbus.frameworks.filemanager

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.IsFramework
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.api.NimbusFileRepository
import io.github.giovanniandreuzza.nimbus.frameworks.filemanager.errors.FileCreationFailed
import io.github.giovanniandreuzza.nimbus.frameworks.filemanager.errors.FileNotFound
import okio.Sink
import okio.Source

/**
 * Nimbus File Manager.
 *
 * @param nimbusFileRepository The Nimbus File Repository.
 * @author Giovanni Andreuzza
 */
@IsFramework
internal class NimbusFileManager(
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
}