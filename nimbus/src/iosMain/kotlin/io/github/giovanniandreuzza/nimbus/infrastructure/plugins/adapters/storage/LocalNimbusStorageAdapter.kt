package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.adapters.storage

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.IsFramework
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.CreateFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.DeleteFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.DoesFileExistError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSinkError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSourceError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.storage.NimbusStoragePort
import kotlinx.io.Sink
import kotlinx.io.Source

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@IsFramework
public actual class LocalNimbusStorageAdapter actual constructor() : NimbusStoragePort {
    actual override fun exists(path: String): KResult<Boolean, DoesFileExistError> {
        TODO("Not yet implemented")
    }

    actual override fun create(path: String): KResult<Unit, CreateFileError> {
        TODO("Not yet implemented")
    }

    actual override fun size(path: String): KResult<Long, GetFileSizeError> {
        TODO("Not yet implemented")
    }

    actual override fun sink(
        path: String,
        hasToAppend: Boolean
    ): KResult<Sink, GetFileSinkError> {
        TODO("Not yet implemented")
    }

    actual override fun source(path: String): KResult<Source, GetFileSourceError> {
        TODO("Not yet implemented")
    }

    actual override fun delete(path: String): KResult<Unit, DeleteFileError> {
        TODO("Not yet implemented")
    }
}