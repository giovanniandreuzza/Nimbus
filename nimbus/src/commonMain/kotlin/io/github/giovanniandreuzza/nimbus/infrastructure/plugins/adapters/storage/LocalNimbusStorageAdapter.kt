package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.adapters.storage

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
public expect class LocalNimbusStorageAdapter() : NimbusStoragePort {
    override fun exists(path: String): KResult<Boolean, DoesFileExistError>
    override fun create(path: String): KResult<Unit, CreateFileError>
    override fun size(path: String): KResult<Long, GetFileSizeError>
    override fun sink(
        path: String,
        hasToAppend: Boolean
    ): KResult<Sink, GetFileSinkError>

    override fun source(path: String): KResult<Source, GetFileSourceError>
    override fun delete(path: String): KResult<Unit, DeleteFileError>
}