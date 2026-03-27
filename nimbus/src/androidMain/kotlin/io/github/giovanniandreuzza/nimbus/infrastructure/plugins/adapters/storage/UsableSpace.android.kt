package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.adapters.storage

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetUsableSpaceError
import java.io.File

internal actual fun queryUsableSpaceBytes(path: String): KResult<Long, GetUsableSpaceError> {
    return try {
        val f = File(path)
        val base = f.parentFile?.takeIf { it.exists() } ?: f
        val u = base.usableSpace
        if (u <= 0L) {
            Failure(
                GetUsableSpaceError.IoFailed(
                    KError("usable_space_non_positive", "usableSpace returned $u")
                )
            )
        } else {
            Success(u)
        }
    } catch (t: Throwable) {
        Failure(GetUsableSpaceError.IoFailed(KError("usable_space_io", t.message ?: "IO error")))
    }
}
