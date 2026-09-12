package io.github.giovanniandreuzza.nimbus

import android.content.Context
import java.io.File

public fun Nimbus.Builder.withAndroidContext(
    context: Context,
    folderName: String = "nimbus"
): Nimbus.Builder {
    val folder = File(context.filesDir, folderName).also { it.mkdirs() }
    return withDownloadManagerPath(File(folder, "download_manager").absolutePath)
}
