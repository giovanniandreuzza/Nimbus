package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.models.IsFrameworkDto
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Checksum Store.
 *
 * Serial names and proto numbers are the on-disk format: moving or renaming this class is
 * safe only while the [SerialName] value stays untouched.
 *
 * [algorithm] is stored by name rather than by ordinal so that adding a second algorithm
 * cannot silently reinterpret already-written digests.
 *
 * @param algorithm Name of the [io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm].
 * @param value Lowercase hex digest.
 * @author Giovanni Andreuzza
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("nimbus.checksum")
@IsFrameworkDto
internal data class ChecksumStore(
    @ProtoNumber(1)
    val algorithm: String,
    @ProtoNumber(2)
    val value: String
)
