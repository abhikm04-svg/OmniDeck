package com.omnideck.kernel.lifecycle

import com.google.common.truth.Truth.assertThat
import com.omnideck.sdk.ModuleId
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * QA-6. Attribution is the prerequisite for per-module error budgets, for routing an
 * incident to `manifest.owner`, and for the quarantine counter to mean anything —
 * which is why the version of this that silently attributed everything to the Shell
 * was worth finding.
 */
class ModuleCrashAttributorTest {

    @Test
    fun `a crash in a module's own code is attributed to that module`() = runTest {
        val fixture = LifecycleFixture()
        fixture.manager.discover()
        val attributor = ModuleCrashAttributor(fixture.manager)

        val thrown = throwableWithFrames("$TEST_MODULE.notes.NotesRepository", "com.omnideck.kernel.router.RouterImpl")

        assertThat(attributor.attribute(thrown)).isEqualTo(ModuleId(TEST_MODULE))
        assertThat(attributor.label(thrown)).isEqualTo(TEST_MODULE)
    }

    @Test
    fun `a crash with no module frame belongs to the Shell`() = runTest {
        val fixture = LifecycleFixture()
        fixture.manager.discover()
        val attributor = ModuleCrashAttributor(fixture.manager)

        val thrown = throwableWithFrames("com.omnideck.shell.MainActivity", "android.app.Activity")

        assertThat(attributor.attribute(thrown)).isNull()
        assertThat(attributor.label(thrown)).isEqualTo("shell")
    }

    @Test
    fun `the topmost module frame wins, not the deepest`() = runTest {
        val fixture = LifecycleFixture()
        fixture.manager.discover()
        val attributor = ModuleCrashAttributor(fixture.manager)

        // A module calling a shared helper that throws is still the module's fault;
        // the helper is used by everyone and owns nothing.
        val thrown = throwableWithFrames(
            "$TEST_MODULE.ui.Editor",
            "com.omnideck.core.Outcome",
        )

        assertThat(attributor.attribute(thrown)).isEqualTo(ModuleId(TEST_MODULE))
    }

    @Test
    fun `a module exception wrapped by the platform is still attributed to the module`() = runTest {
        val fixture = LifecycleFixture()
        fixture.manager.discover()
        val attributor = ModuleCrashAttributor(fixture.manager)

        // Coroutines and Room both routinely rewrap. Stopping at the outer frame
        // would attribute every wrapped failure to whoever did the wrapping.
        val cause = throwableWithFrames("$TEST_MODULE.data.NotesDao")
        val wrapper = RuntimeException("wrapped", cause).apply {
            stackTrace = arrayOf(frame("kotlinx.coroutines.BuildersKt"))
        }

        assertThat(attributor.attribute(wrapper)).isEqualTo(ModuleId(TEST_MODULE))
    }

    @Test
    fun `a cause cycle terminates instead of hanging`() = runTest {
        val fixture = LifecycleFixture()
        fixture.manager.discover()
        val attributor = ModuleCrashAttributor(fixture.manager)

        // Not hypothetical: initCause loops do occur, and an attributor that spins
        // here would hang the crash handler rather than report the crash.
        val first = RuntimeException("a").apply { stackTrace = arrayOf(frame("com.example.A")) }
        val second = RuntimeException("b", first).apply { stackTrace = arrayOf(frame("com.example.B")) }
        first.initCause(second)

        assertThat(attributor.attribute(second)).isNull()
    }

    @Test
    fun `nothing is attributed before discovery has run`() {
        val fixture = LifecycleFixture()
        val attributor = ModuleCrashAttributor(fixture.manager)

        assertThat(attributor.attribute(throwableWithFrames("$TEST_MODULE.Anything"))).isNull()
    }

    @Test
    fun `the owning team comes from the module's manifest, once it is known`() = runTest {
        val fixture = LifecycleFixture()
        fixture.manager.discover()
        val attributor = ModuleCrashAttributor(fixture.manager)

        assertThat(attributor.ownerOf(moduleId())).isNull()

        fixture.manager.activate(moduleId())

        assertThat(attributor.ownerOf(moduleId())?.value).isEqualTo("platform")
    }

    @Test
    fun `a class merely prefixed by a module id is not that module`() = runTest {
        val fixture = LifecycleFixture()
        fixture.manager.discover()
        val attributor = ModuleCrashAttributor(fixture.manager)

        // "com.omnideck.notesfoo.X" starts with the id's characters but is a
        // different package; matching on the dot is what keeps them apart.
        assertThat(attributor.attribute(throwableWithFrames("${TEST_MODULE}foo.X"))).isNull()
    }

    private fun throwableWithFrames(vararg classNames: String) =
        RuntimeException("boom").apply { stackTrace = classNames.map(::frame).toTypedArray() }

    private fun frame(className: String) = StackTraceElement(className, "method", "File.kt", 1)
}
