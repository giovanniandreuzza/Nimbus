@file:OptIn(ExperimentalSerializationApi::class)

package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.models.IsFrameworkDto
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Download state store.
 *
 * Each subclass carries an explicit [SerialName]: it is the polymorphic
 * discriminator written into the store file. Without it the discriminator would
 * default to the class name, and any package move would make every store
 * already on disk undecodable.
 *
 * @author Giovanni Andreuzza
 */
@Serializable
@SerialName("nimbus.state")
@IsFrameworkDto
internal sealed class DownloadStateStore {

    /**
     * Enqueued state.
     */
    @Serializable
    @SerialName("nimbus.state.enqueued")
    data object Enqueued : DownloadStateStore()

    /**
     * Downloading state.
     *
     * @param progress Download progress.
     */
    @Serializable
    @SerialName("nimbus.state.downloading")
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
    @SerialName("nimbus.state.paused")
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
    @SerialName("nimbus.state.failed")
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
    @SerialName("nimbus.state.finished")
    data object Finished : DownloadStateStore()

    /**
     * Cancelled state.
     */
    @Serializable
    @SerialName("nimbus.state.cancelled")
    data object Cancelled : DownloadStateStore()
}
