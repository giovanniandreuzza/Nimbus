package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.adapters.storage

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.IsFramework
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
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSourceError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.storage.NimbusStoragePort
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@IsFramework
public actual class LocalNimbusStorageAdapter actual constructor() : NimbusStoragePort {
    actual override fun exists(path: String): KResult<Boolean, DoesFileExistError> {
        return try {
            val file = File(path)
            Success(file.exists())
        } catch (e: SecurityException) {
            val error = KError(
                code = "read_permission_denied",
                message = e.message ?: "Read permission denied"
            )
            Failure(DoesFileExistError.ReadPermissionDenied(error))
        }
    }

    actual override fun create(path: String): KResult<Unit, CreateFileError> {
        exists(path).onFailure {
            return Failure(CreateFileError.ReadPermissionDenied(it.cause))
        }.onSuccess { exists ->
            if (exists) {
                return Failure(CreateFileError.FileAlreadyExists)
            }
        }
        return try {
            val file = File(path)
            if (!file.createNewFile()) {
                return Failure(CreateFileError.FileAlreadyExists)
            }
            Success(Unit)
        } catch (e: IOException) {
            val error = KError(
                code = "io_error",
                message = e.message ?: "IO Error during file creation"
            )
            Failure(CreateFileError.IOError(error))
        } catch (e: SecurityException) {
            val error = KError(
                code = "write_permission_denied",
                message = e.message ?: "Write permission denied"
            )
            Failure(CreateFileError.WritePermissionDenied(error))
        }
    }

    actual override fun size(path: String): KResult<Long, GetFileSizeError> {
        exists(path).onFailure {
            return Failure(GetFileSizeError.ReadPermissionDenied(it.cause))
        }.onSuccess { exists ->
            if (!exists) {
                return Failure(GetFileSizeError.FileNotFound)
            }
        }
        return try {
            val file = File(path)
            Success(file.length())
        } catch (e: SecurityException) {
            val error = KError(
                code = "read_permission_denied",
                message = e.message ?: "Read permission denied"
            )
            Failure(GetFileSizeError.ReadPermissionDenied(error))
        }
    }

    actual override fun sink(
        path: String,
        hasToAppend: Boolean
    ): KResult<Sink, GetFileSinkError> {
        exists(path).onFailure {
            return Failure(GetFileSinkError.ReadPermissionDenied(it.cause))
        }.onSuccess { exists ->
            if (!exists) {
                return Failure(GetFileSinkError.FileNotFound)
            }
        }
        return try {
            Success(FileOutputStream(path, hasToAppend).asSink().buffered())
        } catch (_: FileNotFoundException) {
            Failure(GetFileSinkError.FileNotFound)
        } catch (e: SecurityException) {
            val error = KError(
                code = "write_permission_denied",
                message = e.message ?: "Write permission denied"
            )
            Failure(GetFileSinkError.WritePermissionDenied(error))
        }
    }

    actual override fun source(path: String): KResult<Source, GetFileSourceError> {
        exists(path).onFailure {
            return Failure(GetFileSourceError.ReadPermissionDenied(it.cause))
        }.onSuccess { exists ->
            if (!exists) {
                return Failure(GetFileSourceError.FileNotFound)
            }
        }
        return try {
            Success(FileInputStream(path).asSource().buffered())
        } catch (_: FileNotFoundException) {
            Failure(GetFileSourceError.FileNotFound)
        } catch (e: SecurityException) {
            val error = KError(
                code = "read_permission_denied",
                message = e.message ?: "Read permission denied"
            )
            Failure(GetFileSourceError.ReadPermissionDenied(error))
        }
    }

    actual override fun delete(path: String): KResult<Unit, DeleteFileError> {
        exists(path).onFailure {
            return Failure(DeleteFileError.ReadPermissionDenied(it.cause))
        }.onSuccess { exists ->
            if (!exists) {
                return Failure(DeleteFileError.FileNotFound)
            }
        }
        return try {
            val file = File(path)
            if (file.delete()) {
                Success(Unit)
            } else {
                Failure(DeleteFileError.DeleteFailed)
            }
        } catch (e: IOException) {
            val error = KError(
                code = "io_error",
                message = e.message ?: "IO Error during file deletion"
            )
            Failure(DeleteFileError.IOError(error))
        } catch (e: SecurityException) {
            val error = KError(
                code = "delete_permission_denied",
                message = e.message ?: "Delete permission denied"
            )
            Failure(DeleteFileError.DeletePermissionDenied(error))
        }
    }
}