package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.adapters.storage

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onFailure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onSuccess
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.CreateFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.DeleteFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.DoesFileExistError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSinkError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.LocalFileSizeError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetUsableSpaceError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSourceError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.MoveFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.storage.NimbusStoragePort
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

internal class FileSystemNimbusStorageAdapter : NimbusStoragePort {
    private val fileSystem = SystemFileSystem

    override fun exists(path: String): KResult<Boolean, DoesFileExistError> {
        return try {
            Success(fileSystem.exists(Path(path)))
        } catch (e: IOException) {
            Failure(DoesFileExistError.ReadPermissionDenied(readPermissionError(e)))
        } catch (t: Throwable) {
            Failure(DoesFileExistError.ReadPermissionDenied(readPermissionError(t)))
        }
    }

    override fun create(path: String): KResult<Unit, CreateFileError> {
        exists(path).onFailure {
            return Failure(CreateFileError.ReadPermissionDenied(it.cause))
        }.onSuccess { exists ->
            if (exists) return Failure(CreateFileError.FileAlreadyExists)
        }

        return try {
            val filePath = Path(path)
            filePath.parent?.let { parent ->
                fileSystem.createDirectories(parent)
            }
            fileSystem.sink(path = filePath, append = false).buffered().use { sink ->
                sink.flush()
            }
            Success(Unit)
        } catch (e: IOException) {
            Failure(CreateFileError.IOError(ioError(e, "IO Error during file creation")))
        } catch (t: Throwable) {
            Failure(CreateFileError.WritePermissionDenied(writePermissionError(t)))
        }
    }

    override fun usableSpaceBytes(path: String): KResult<Long, GetUsableSpaceError> =
        queryUsableSpaceBytes(path)

    override fun size(path: String): KResult<Long, LocalFileSizeError> {
        exists(path).onFailure {
            return Failure(LocalFileSizeError.ReadPermissionDenied(it.cause))
        }.onSuccess { exists ->
            if (!exists) return Failure(LocalFileSizeError.FileNotFound)
        }

        return try {
            val metadata = fileSystem.metadataOrNull(Path(path))
                ?: return Failure(LocalFileSizeError.FileNotFound)
            Success(metadata.size ?: return Failure(LocalFileSizeError.FileNotFound))
        } catch (e: IOException) {
            Failure(LocalFileSizeError.ReadPermissionDenied(readPermissionError(e)))
        } catch (t: Throwable) {
            Failure(LocalFileSizeError.ReadPermissionDenied(readPermissionError(t)))
        }
    }

    override fun sink(path: String, hasToAppend: Boolean): KResult<Sink, GetFileSinkError> {
        exists(path).onFailure {
            return Failure(GetFileSinkError.ReadPermissionDenied(it.cause))
        }.onSuccess { exists ->
            if (!exists) return Failure(GetFileSinkError.FileNotFound)
        }

        return try {
            Success(fileSystem.sink(Path(path), append = hasToAppend).buffered())
        } catch (_: FileNotFoundException) {
            Failure(GetFileSinkError.FileNotFound)
        } catch (e: IOException) {
            Failure(GetFileSinkError.WritePermissionDenied(writePermissionError(e)))
        } catch (t: Throwable) {
            Failure(GetFileSinkError.WritePermissionDenied(writePermissionError(t)))
        }
    }

    override fun source(path: String): KResult<Source, GetFileSourceError> {
        exists(path).onFailure {
            return Failure(GetFileSourceError.ReadPermissionDenied(it.cause))
        }.onSuccess { exists ->
            if (!exists) return Failure(GetFileSourceError.FileNotFound)
        }

        return try {
            Success(fileSystem.source(Path(path)).buffered())
        } catch (_: FileNotFoundException) {
            Failure(GetFileSourceError.FileNotFound)
        } catch (e: IOException) {
            Failure(GetFileSourceError.ReadPermissionDenied(readPermissionError(e)))
        } catch (t: Throwable) {
            Failure(GetFileSourceError.ReadPermissionDenied(readPermissionError(t)))
        }
    }

    override fun delete(path: String): KResult<Unit, DeleteFileError> {
        exists(path).onFailure {
            return Failure(DeleteFileError.ReadPermissionDenied(it.cause))
        }.onSuccess { exists ->
            if (!exists) return Failure(DeleteFileError.FileNotFound)
        }

        return try {
            fileSystem.delete(Path(path), mustExist = true)
            Success(Unit)
        } catch (_: FileNotFoundException) {
            Failure(DeleteFileError.FileNotFound)
        } catch (e: IOException) {
            Failure(DeleteFileError.IOError(ioError(e, "IO Error during file deletion")))
        } catch (t: Throwable) {
            Failure(DeleteFileError.DeletePermissionDenied(deletePermissionError(t)))
        }
    }

    override fun atomicMove(sourcePath: String, destinationPath: String): KResult<Unit, MoveFileError> {
        return try {
            fileSystem.atomicMove(Path(sourcePath), Path(destinationPath))
            Success(Unit)
        } catch (_: FileNotFoundException) {
            Failure(MoveFileError.FileNotFound)
        } catch (e: IOException) {
            Failure(MoveFileError.IOError(ioError(e, "IO Error during file move")))
        } catch (t: Throwable) {
            Failure(MoveFileError.WritePermissionDenied(writePermissionError(t)))
        }
    }

    private fun ioError(cause: Throwable, message: String): KError =
        KError(code = "io_error", message = cause.message ?: message)

    private fun readPermissionError(cause: Throwable): KError =
        KError(code = "read_permission_denied", message = cause.message ?: "Read permission denied")

    private fun writePermissionError(cause: Throwable): KError =
        KError(code = "write_permission_denied", message = cause.message ?: "Write permission denied")

    private fun deletePermissionError(cause: Throwable): KError =
        KError(code = "delete_permission_denied", message = cause.message ?: "Delete permission denied")
}
