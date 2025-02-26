package io.github.giovanniandreuzza.nimbus.frameworks.filemanager.models

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.models.IsFrameworkDto
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Download Task Store.
 *
 * @author Giovanni Andreuzza
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@IsFrameworkDto
internal data class DownloadTaskStore(
    @ProtoNumber(1)
    val id: String,
    @ProtoNumber(2)
    val fileName: String,
    @ProtoNumber(3)
    val fileUrl: String,
    @ProtoNumber(4)
    val filePath: String,
    @ProtoNumber(5)
    val fileSize: Long,
    @ProtoNumber(6)
    val state: DownloadStateStore,
    @ProtoNumber(7)
    val version: Int
)