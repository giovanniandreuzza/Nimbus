package io.github.giovanniandreuzza.sample_android.infrastructure

import io.github.giovanniandreuzza.nimbus.core.ports.FileRepository
import okio.Sink
import okio.sink
import java.io.File
import java.io.FileOutputStream

/**
 * Local File Repository Adapter.
 *
 * @author Giovanni Andreuzza
 */
class LocalFileRepository : FileRepository {

    override fun isDownloaded(filePath: String): Boolean {
        val file = File(filePath)
        return file.exists()
    }

    override fun getFileSize(filePath: String): Long {
        val file = File(filePath)

        if (!isDownloaded(filePath)) {
            return 0
        }

        return file.length()
    }

    override fun getSink(filePath: String): Sink {
        val file = File(filePath)

        if (!file.exists()) {
            file.createNewFile()
        }

        return FileOutputStream(file, true).sink()
    }

    override fun deleteFile(filePath: String) {
        val file = File(filePath)

        if (!isDownloaded(filePath)) {
            return
        }

        file.delete()
    }
}