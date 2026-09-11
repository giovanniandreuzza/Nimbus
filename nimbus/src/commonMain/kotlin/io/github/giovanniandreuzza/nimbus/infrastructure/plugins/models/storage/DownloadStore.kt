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
    val schemaVersion: Int = UNSTAMPED
) {
    internal companion object {
        /**
         * Bump whenever the meaning of the persisted fields changes.
         *
         * A store stamped **higher** than this is discarded on load: protobuf skips
         * unknown fields silently, so without the stamp a blob written by a newer build
         * would be read back as if this build understood it.
         *
         * A store stamped **lower** is read and migrated. Discarding it would throw away
         * every pending download on an app update, which on an unattended device means
         * re-fetching bytes it had already paid for.
         *
         * - 1: initial format.
         * - 2: adds the optional `expectedChecksum` and `checksum` fields to each task.
         *   Both are absent in a version 1 blob and decode as null, so a version 1 blob
         *   is already a valid version 2 blob.
         */
        const val SCHEMA_VERSION: Int = 2

        /**
         * What a store written before the stamp reached the disk decodes as.
         *
         * Every build up to 2.2.0 defaulted this field to its own current version, and
         * ProtoBuf omits a value equal to its default — so those stores carry no stamp at
         * all, and a default of "current" would have every one of them claim to be
         * whatever the reading build is. Zero is a value no build ever wrote, so it means
         * exactly "older than the stamp" and routes to the migration path.
         */
        const val UNSTAMPED: Int = 0
    }
}
