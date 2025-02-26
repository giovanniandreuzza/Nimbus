package io.github.giovanniandreuzza.sample_android.infrastructure

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.api.NimbusFileRepository
import io.github.giovanniandreuzza.nimbus.frameworks.filemanager.errors.FileCreationFailed
import io.github.giovanniandreuzza.nimbus.frameworks.filemanager.errors.FileNotFound
import okio.Sink
import okio.Source
import okio.sink
import okio.source
import java.io.File
import java.io.FileOutputStream

/**
 * Local File Repository Adapter.
 *
 * @author Giovanni Andreuzza
 */
class LocalNimbusFileRepository : NimbusFileRepository {

    /**
     * Check if the file is downloaded.
     */
    override fun exists(filePath: String): Boolean {
        val file = File(filePath)
        return file.exists()
    }

    override fun createFile(filePath: String): KResult<Unit, FileCreationFailed> {
        val file = File(filePath)

        if (file.exists()) {
            return Success(Unit)
        }

        return try {
            file.createNewFile()
            Success(Unit)
        } catch (e: Exception) {
            Failure(FileCreationFailed(e.message ?: "File creation failed"))
        }
    }

    override fun getFileSize(filePath: String): KResult<Long, FileNotFound> {
        val file = File(filePath)

        if (!file.exists()) {
            return Failure(FileNotFound("File does not exist"))
        }

        return Success(file.length())
    }

    override fun getFileSink(filePath: String, hasToAppend: Boolean): KResult<Sink, FileNotFound> {
        val file = File(filePath)

        if (!file.exists()) {
            file.createNewFile()
        }

        val sink = FileOutputStream(file, hasToAppend).sink()

        return Success(sink)
    }

    override fun getFileSource(filePath: String): KResult<Source, FileNotFound> {
        val file = File(filePath)

        if (!file.exists()) {
            return Failure(FileNotFound("File does not exist"))
        }

        val source = file.source()

        return Success(source)
    }

    override fun deleteFile(filePath: String): KResult<Unit, FileNotFound> {
        val file = File(filePath)

        if (!file.exists()) {
            return Failure(FileNotFound("File does not exist"))
        }

        file.delete()

        return Success(Unit)
    }
}