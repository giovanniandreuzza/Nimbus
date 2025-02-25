@file:OptIn(ExperimentalSerializationApi::class)

package io.github.giovanniandreuzza.nimbus.infrastructure.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Download state store.
 *
 * @author Giovanni Andreuzza
 */
@Serializable
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
     */
    @Serializable
    data class Failed(
        @ProtoNumber(1)
        val errorCode: String,
        @ProtoNumber(2)
        val errorMessage: String
    ) : DownloadStateStore()

    /**
     * Finished state.
     */
    @Serializable
    data object Finished : DownloadStateStore()
}