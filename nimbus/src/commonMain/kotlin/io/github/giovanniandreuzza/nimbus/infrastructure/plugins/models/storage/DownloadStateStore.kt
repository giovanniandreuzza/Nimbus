@file:OptIn(ExperimentalSerializationApi::class)

package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.models.IsFrameworkDto
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Download state store.
 *
 * @author Giovanni Andreuzza
 */
@Serializable
@IsFrameworkDto
internal sealed class DownloadStateStore {

    /**
     * Enqueued state.
     */
    @Serializable
    data object Enqueued : DownloadStateStore()

    /**
     * Downloading state.
     *
     * @param progress Download progress.
     */
    @Serializable
    data class Downloading(
        @ProtoNumber(1)
        val progress: Double
    ) : DownloadStateStore()

    /**
     * Paused state.
     *
     * @param progress Download progress.
     */
    @Serializable
    data class Paused(
        @ProtoNumber(1)
        val progress: Double
    ) : DownloadStateStore()

    /**
     * Failed state.
     *
     * @param errorCode Error code.
     * @param errorMessage Error message.
     * @param errorCause Error cause.
     */
    @Serializable
    data class Failed(
        @ProtoNumber(1)
        val errorCode: String,
        @ProtoNumber(2)
        val errorMessage: String,
        @ProtoNumber(3)
        val errorCause: Failed? = null
    ) : DownloadStateStore()

    /**
     * Finished state.
     */
    @Serializable
    data object Finished : DownloadStateStore()
}