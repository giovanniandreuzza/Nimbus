package io.github.giovanniandreuzza.nimbus.core.ports

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult

/**
 * The storage core depends on.
 *
 * Downloads already cross this boundary cleanly: [DownloadPort] is core-owned and internal,
 * `DownloadAdapter` implements it, and nothing in core names the public download plugin.
 * Storage had the identical need and no such layer, so `DownloadService` reached outward for
 * the plugin interface and three of its error types — the only outward imports anywhere in
 * `core/`. This is the missing half of that symmetry.
 *
 * The surface is the four operations core actually performs. Never `sink`, `source`,
 * `exists` or `atomicMove`: those belong to components outside core, which are entitled to
 * use the plugin directly and do.
 *
 * @author Giovanni Andreuzza
 */
internal interface StoragePort {

    /** Size in bytes of the file at [path]. */
    fun size(path: String): KResult<Long, StoragePortError>

    /** Creates the file at [path], reporting an existing file as [CreateOutcome.AlreadyExists]. */
    fun create(path: String): KResult<CreateOutcome, StoragePortError>

    /** Deletes the file at [path], reporting an absent file as [DeleteOutcome.NotFound]. */
    fun delete(path: String): KResult<DeleteOutcome, StoragePortError>

    /**
     * Free bytes on the volume holding [path], or `null` where the platform cannot answer.
     */
    fun usableSpaceBytes(path: String): KResult<Long?, StoragePortError>
}

/**
 * Whether [StoragePort.create] had to make the file.
 *
 * A file that was already there is not a failure — every caller wanted it to exist and it
 * does. Modelling it as one meant each call site had to remember to exempt it:
 *
 * ```
 * storage.create(path).onFailure {
 *     if (it !is CreateFileError.FileAlreadyExists) return Failure(...)
 * }
 * ```
 *
 * That shape was written out by hand at every site, correct each time and independently
 * forgettable. As a success value the omission cannot be written.
 */
internal enum class CreateOutcome { Created, AlreadyExists }

/**
 * Whether [StoragePort.delete] had to remove anything. A file that was already gone is the
 * outcome the caller asked for — see [CreateOutcome] for why that is a success.
 */
internal enum class DeleteOutcome { Deleted, NotFound }
