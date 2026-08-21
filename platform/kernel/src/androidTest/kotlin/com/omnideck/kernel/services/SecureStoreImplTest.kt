package com.omnideck.kernel.services

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.omnideck.core.DispatcherProvider
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.capability.getString
import com.omnideck.sdk.capability.putString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * Instrumented because there is no substitute for the real thing: `AndroidKeyStore`
 * has no JVM provider and Robolectric does not implement key generation, so this is
 * the only place `SecureStoreImpl`'s crypto actually executes.
 *
 * Runs on any connected device or emulator. An emulator exercises the TEE-backed path
 * and the StrongBox *fallback*; only a device with a hardware security module (Pixel
 * 6+, for instance) exercises StrongBox succeeding — see the last test.
 */
@RunWith(AndroidJUnit4::class)
class SecureStoreImplTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val unconfined = Dispatchers.Unconfined
    }

    private fun store(moduleId: String) = SecureStoreImpl(context, ModuleId(moduleId), dispatchers)

    @Before
    fun clearKeystoreAndFiles() {
        // Both halves outlive the test: Keystore entries survive the process, and the
        // ciphertext files survive in app storage. Clearing only one leaves tests
        // order-dependent — files from an earlier case, keys from an earlier run.
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.aliases().toList()
            .filter { it.startsWith("omnideck.module.") }
            .forEach { runCatching { ks.deleteEntry(it) } }

        File(context.noBackupFilesDir, "secure").deleteRecursively()
    }

    @Test
    fun roundTripsAValue() = runTest {
        val store = store("com.omnideck.notes")

        store.putString("token", "s3cret")

        assertThat(store.getString("token")).isEqualTo("s3cret")
    }

    @Test
    fun reportsPresenceAndDeletes() = runTest {
        val store = store("com.omnideck.notes")
        store.putString("token", "v")
        assertThat(store.contains("token")).isTrue()

        store.delete("token")

        assertThat(store.contains("token")).isFalse()
        assertThat(store.get("token")).isNull()
    }

    @Test
    fun returnsNullForAnUnknownAlias() = runTest {
        assertThat(store("com.omnideck.notes").get("never-written")).isNull()
    }

    @Test
    fun overwritesAnExistingAlias() = runTest {
        val store = store("com.omnideck.notes")
        store.putString("token", "first")

        store.putString("token", "second")

        assertThat(store.getString("token")).isEqualTo("second")
    }

    @Test
    fun storesBinaryValuesIntact() = runTest {
        val store = store("com.omnideck.notes")
        val bytes = ByteArray(256) { it.toByte() }

        store.put("blob", bytes)

        assertThat(store.get("blob")).isEqualTo(bytes)
    }

    /**
     * The isolation claim of architecture.md §12.2. Keys are aliased per module, so
     * one module's ciphertext must be unreadable by another even though both are in
     * the same process with the same filesystem access.
     */
    @Test
    fun oneModuleCannotReadAnothersSecret() = runTest {
        store("com.omnideck.notes").putString("token", "notes-secret")

        val otherModule = store("com.omnideck.finance")

        assertThat(otherModule.get("token")).isNull()
    }

    @Test
    fun eachModuleKeepsItsOwnValueUnderTheSameAlias() = runTest {
        store("com.omnideck.notes").putString("token", "notes-value")
        store("com.omnideck.finance").putString("token", "finance-value")

        assertThat(store("com.omnideck.notes").getString("token")).isEqualTo("notes-value")
        assertThat(store("com.omnideck.finance").getString("token")).isEqualTo("finance-value")
    }

    /** Aliases become file names, so a traversal attempt must not escape the module dir. */
    @Test
    fun sanitisesAliasesThatWouldEscapeTheModuleDirectory() = runTest {
        val store = store("com.omnideck.notes")

        store.putString("../../evil", "payload")

        assertThat(store.getString("../../evil")).isEqualTo("payload")
        val noBackup = context.noBackupFilesDir
        assertThat(File(noBackup, "secure/com.omnideck.notes").listFiles()?.map { it.name })
            .containsExactly(".._.._evil")
    }

    @Test
    fun ciphertextOnDiskDoesNotContainThePlaintext() = runTest {
        val store = store("com.omnideck.notes")

        store.putString("token", "PLAINTEXT_MARKER")

        val file = File(context.noBackupFilesDir, "secure/com.omnideck.notes").listFiles()!!.single()
        assertThat(String(file.readBytes(), Charsets.ISO_8859_1)).doesNotContain("PLAINTEXT_MARKER")
    }

    /**
     * Biometric gating is stubbed until OD-403 (Phase 4). Pinned so that ticket has to
     * update this test rather than the stub being mistaken for working behaviour.
     */
    @Test
    fun biometricGatingIsNotImplementedYet() = runTest {
        val store = store("com.omnideck.notes")

        assertThat(store.putBiometricGated("k", byteArrayOf(1))).isFalse()
        assertThat(store.getBiometricGated("k", "Unlock")).isNull()
    }
}
