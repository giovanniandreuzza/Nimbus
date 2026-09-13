package io.github.giovanniandreuzza.nimbus.shared.utils

import io.github.giovanniandreuzza.explicitarchitecture.shared.IsShared

/**
 * The path with its separators unified, its empty and `.` segments dropped, and its `..`
 * segments resolved against what precedes them.
 *
 * Lexical, deliberately: this runs before anything touches the filesystem — the destination
 * usually does not exist yet — and it must give the same answer on a device where the parent
 * directory is missing as on one where it is not.
 */
@IsShared
internal fun String.normalizedPath(): String {
    val unified = replace('\\', '/')
    val absolute = unified.startsWith("/")
    val resolved = ArrayDeque<String>()

    for (segment in unified.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (resolved.isNotEmpty() && resolved.last() != "..") {
                resolved.removeLast()
            } else if (!absolute) {
                // A relative path may genuinely start above itself; an absolute one cannot go
                // above the root, and `/..` is `/`.
                resolved.addLast("..")
            }

            else -> resolved.addLast(segment)
        }
    }

    val body = resolved.joinToString("/")
    return if (absolute) "/$body" else body
}

/**
 * Whether this path, once normalised, is [root] or something under it.
 *
 * A string comparison, and that is its limit: a symlink inside the root pointing somewhere
 * else still leads out of it, and nothing here can see that without asking the filesystem —
 * which cannot answer for a file that does not exist yet. It stops the case that actually
 * happens, which is a path from a manifest naming somewhere it should not.
 */
@IsShared
internal fun String.isInside(root: String): Boolean {
    val normalizedRoot = root.normalizedPath().trimEnd('/')
    val normalized = normalizedPath()
    if (normalizedRoot.isEmpty()) return true
    return normalized == normalizedRoot || normalized.startsWith("$normalizedRoot/")
}
