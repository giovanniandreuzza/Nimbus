package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.adapters.storage

import kotlinx.cinterop.ExperimentalForeignApi

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetUsableSpaceError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber

/**
 * Uses filesystem attributes for the volume containing [path]'s directory.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun queryUsableSpaceBytes(path: String): KResult<Long, GetUsableSpaceError> {
    val dir = path.substringBeforeLast('/').ifEmpty { "/" }
    return try {
        val attrs = NSFileManager.defaultManager.attributesOfFileSystemForPath(dir, null)
            ?: return Failure(
                GetUsableSpaceError.IoFailed(
                    KError("no_fs_attrs", "Could not read filesystem attributes for: $dir")
                )
            )
        val raw = attrs[NSFileSystemFreeSize]
        val num = raw as? NSNumber
            ?: return Failure(
                GetUsableSpaceError.IoFailed(
                    KError("no_free_size", "NSFileSystemFreeSize missing for: $dir")
                )
            )
        val free = num.longLongValue
        if (free <= 0L) {
            Failure(
                GetUsableSpaceError.IoFailed(
                    KError("usable_space_non_positive", "NSFileSystemFreeSize returned $free")
                )
            )
        } else {
            Success(free)
        }
    } catch (t: Throwable) {
        Failure(GetUsableSpaceError.IoFailed(KError("usable_space_io", t.message ?: "IO error")))
    }
}
