package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.models.IsFrameworkDto
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Download Store.
 *
 * Serial names and proto numbers are the on-disk format: moving or renaming
 * these classes is safe only while the [SerialName] values stay untouched.
 *
 * @param downloads Download tasks.
 * @param schemaVersion Version of the persisted format, see [SCHEMA_VERSION].
 * @author Giovanni Andreuzza
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("nimbus.store")
@IsFrameworkDto
internal data class DownloadStore(
    @ProtoNumber(1)
    val downloads: Map<String, DownloadTaskStore> = emptyMap(),
    @ProtoNumber(2)
    val schemaVersion: Int = SCHEMA_VERSION
) {
    internal companion object {
        /**
         * Bump whenever the meaning of the persisted fields changes. A store
         * carrying any other value is discarded on load: protobuf skips unknown
         * fields silently, so without the stamp an incompatible blob would be
         * read back as if it were valid.
         */
        const val SCHEMA_VERSION: Int = 1
    }
}
