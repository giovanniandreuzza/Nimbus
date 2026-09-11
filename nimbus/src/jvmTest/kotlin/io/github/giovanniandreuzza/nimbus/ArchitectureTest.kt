package io.github.giovanniandreuzza.nimbus

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Dependencies point inward: `presentation → core ← infrastructure`.
 *
 * A source scan rather than a runtime check, because the rule is about what the code is
 * allowed to name, and because the leak this catches — core reaching outward for a plugin
 * type it happens to need — reappears the moment core needs something only a plugin offers.
 */
class ArchitectureTest {

    @Test
    fun `core does not depend on infrastructure`() {
        val offenders = coreSources()
            .flatMap { file ->
                file.readLines()
                    .withIndex()
                    .filter { (_, line) -> line.trimStart().startsWith(FORBIDDEN_IMPORT) }
                    .map { (index, line) -> "${file.name}:${index + 1}  ${line.trim()}" }
            }

        assertTrue(
            offenders.isEmpty(),
            "core must not import infrastructure, found:\n" + offenders.joinToString("\n")
        )
    }

    private fun coreSources(): List<File> {
        val core = File(moduleRoot(), "src/commonMain/kotlin/$PACKAGE_PATH/core")
        assertTrue(core.isDirectory, "expected to find core sources at $core")
        return core.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** Gradle may run tests from the module directory or from the repository root. */
    private fun moduleRoot(): File {
        var candidate: File? = File(System.getProperty("user.dir"))
        while (candidate != null) {
            if (File(candidate, "src/commonMain/kotlin/$PACKAGE_PATH").isDirectory) return candidate
            val nested = File(candidate, "nimbus")
            if (File(nested, "src/commonMain/kotlin/$PACKAGE_PATH").isDirectory) return nested
            candidate = candidate.parentFile
        }
        error("could not locate the nimbus module from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val PACKAGE_PATH = "io/github/giovanniandreuzza/nimbus"
        const val FORBIDDEN_IMPORT = "import io.github.giovanniandreuzza.nimbus.infrastructure"
    }
}
