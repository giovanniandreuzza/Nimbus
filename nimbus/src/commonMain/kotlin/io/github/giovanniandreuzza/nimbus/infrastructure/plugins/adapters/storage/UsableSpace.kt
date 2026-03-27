package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.adapters.storage

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetUsableSpaceError

/**
 * Platform: usable bytes on the filesystem volume that contains [path] (typically parent of the file).
 */
internal expect fun queryUsableSpaceBytes(path: String): KResult<Long, GetUsableSpaceError>
