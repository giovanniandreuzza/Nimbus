package io.github.giovanniandreuzza.nimbus.infrastructure.time

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

internal actual fun currentEpochMs(): Long =
    (NSDate().timeIntervalSince1970 * 1_000).toLong()
