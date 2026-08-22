package com.omnideck.shell

import com.google.common.truth.Truth.assertThat
import com.omnideck.generated.GeneratedModuleRegistry
import org.junit.Test
import java.io.File

/**
 * The Phase 2 exit gate, mechanised.
 *
 * The plan states it as a review instruction — "OD-209 was implemented without
 * modifying any file under `app/` or `platform/kernel/`, verified by inspecting the
 * PR diff". A rule enforced by inspection holds until the first busy week. This
 * asserts the property the diff review was looking for: **no production source in the
 * Shell or the kernel names any module.**
 *
 * Scope is `src/main` on purpose. A test fixture may legitimately use a module id as
 * sample data; production code may not, because that is the coupling that turns
 * "adding a module is a directory" back into "adding a module is a PR against the
 * Shell".
 */
class ShellIsolationFitnessTest {

    @Test
    fun `no production Shell or kernel source names a module`() {
        val needles = moduleReferences()
        assertThat(needles).isNotEmpty()

        val offenders = productionSources()
            .flatMap { file ->
                val text = file.readText()
                needles.filter { it in text }.map { "${file.relativeTo(repoRoot()).invariantSeparatorsPath}: $it" }
            }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `the Shell's build file names no module either`() {
        val buildFile = repoRoot().resolve("app/build.gradle.kts").readText()

        GeneratedModuleRegistry.factories.keys.forEach { id ->
            assertThat(buildFile).doesNotContain(id)
            assertThat(buildFile).doesNotContain(id.substringAfterLast('.'))
        }
        // The wiring is by discovery, in settings.gradle.kts and the application
        // convention plugin. A literal project dependency here would work, and would
        // quietly end the property this whole test exists to protect.
        assertThat(buildFile).doesNotContain(":modules:")
    }

    /**
     * The forms a module actually leaks in.
     *
     * The full id catches an import, a `ModuleId(...)` literal or a keep rule. The
     * other two catch the short id where it can do damage — a hardcoded route, or a
     * bare string in a `when` on `route.host` — without failing on an English word
     * that happens to match a short id in a comment. A blunt substring match on the
     * short id alone would do the latter constantly and get the test deleted.
     */
    private fun moduleReferences(): Set<String> = GeneratedModuleRegistry.factories.keys
        .flatMap { id ->
            val shortId = id.substringAfterLast('.')
            listOf(id, "omnideck://$shortId", "\"$shortId\"")
        }
        .toSet()

    private fun productionSources(): List<File> = listOf("app/src/main", "platform/kernel/src/main")
        .map(repoRoot()::resolve)
        .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension in SOURCE_EXTENSIONS } }

    /**
     * Walks up rather than assuming a working directory: Gradle, an IDE and CI do not
     * agree on what it is, and a fitness test that silently scans nothing is worse
     * than no fitness test.
     */
    private fun repoRoot(): File {
        var candidate: File? = File("").absoluteFile
        while (candidate != null) {
            if (candidate.resolve("settings.gradle.kts").isFile) return candidate
            candidate = candidate.parentFile
        }
        error("Could not locate the repository root from ${File("").absolutePath}")
    }

    private companion object {
        val SOURCE_EXTENSIONS = setOf("kt", "java", "xml")
    }
}
