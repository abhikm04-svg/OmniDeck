package com.omnideck.kernel.services

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.omnideck.core.DispatcherProvider
import com.omnideck.sdk.ModuleId
import com.omnideck.sdk.capability.SecureStore
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed secret storage.
 *
 * **Key derivation is per module** — the Keystore alias includes the module id — so
 * one module's ciphertext cannot be decrypted by another even with full filesystem
 * access on a rooted device (architecture.md §12.2).
 *
 * StrongBox is requested when the device has a hardware security module and falls
 * back cleanly when it does not; note that `setIsStrongBoxBacked(true)` throws
 * `StrongBoxUnavailableException` at *generation* time rather than returning a flag,
 * which is why generation is wrapped.
 *
 * Scaffold deviation from ADR-007: this implementation uses the Keystore's AES-GCM
 * directly rather than Tink. It is deliberate for Phase 0 — fewer moving parts, no
 * deprecated `AndroidKeysetManager`. Phase 4 (OD-402) migrates to Tink AEAD for
 * envelope encryption and key rotation; the interface does not change.
 */
class SecureStoreImpl(
    private val context: Context,
    private val moduleId: ModuleId,
    private val dispatchers: DispatcherProvider,
) : SecureStore {

    private val keyAlias = "omnideck.module.${moduleId.value}"

    private val storeDir: File
        get() = File(context.noBackupFilesDir, "secure/${moduleId.value}").apply { mkdirs() }

    override suspend fun put(alias: String, value: ByteArray) = withContext(dispatchers.io) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, orCreateKey(requireAuth = false))
        }
        val ciphertext = cipher.doFinal(value)
        // iv length | iv | ciphertext
        File(storeDir, alias.sanitised()).writeBytes(
            byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + ciphertext,
        )
    }

    override suspend fun get(alias: String): ByteArray? = withContext(dispatchers.io) {
        val file = File(storeDir, alias.sanitised())
        if (!file.exists()) return@withContext null

        runCatching {
            val blob = file.readBytes()
            val ivSize = blob[0].toInt()
            val iv = blob.copyOfRange(1, 1 + ivSize)
            val ciphertext = blob.copyOfRange(1 + ivSize, blob.size)
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, existingKey() ?: return@withContext null, GCMParameterSpec(TAG_BITS, iv))
            }.doFinal(ciphertext)
        }.getOrNull()
    }

    override suspend fun delete(alias: String) = withContext(dispatchers.io) {
        File(storeDir, alias.sanitised()).delete()
        Unit
    }

    override suspend fun contains(alias: String): Boolean = withContext(dispatchers.io) {
        File(storeDir, alias.sanitised()).exists()
    }

    override suspend fun putBiometricGated(alias: String, value: ByteArray, timeoutSeconds: Int): Boolean {
        // Requires a BiometricPrompt-driven CryptoObject flow — OD-403, Phase 4.
        return false
    }

    override suspend fun getBiometricGated(alias: String, promptTitle: String, promptSubtitle: String?): ByteArray? =
        null

    // -----------------------------------------------------------------------

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun existingKey(): SecretKey? = (keyStore().getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun orCreateKey(requireAuth: Boolean): SecretKey = existingKey() ?: generateKey(requireAuth)

    private fun generateKey(requireAuth: Boolean): SecretKey {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setUserAuthenticationRequired(requireAuth)
            .apply {
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)

        return runCatching {
            generator.init(spec(strongBox = true))
            generator.generateKey()
        }.getOrElse {
            // No HSM on this device — fall back to TEE-backed keys.
            generator.init(spec(strongBox = false))
            generator.generateKey()
        }
    }

    /** Aliases become file names, so they must not escape the module's directory. */
    private fun String.sanitised(): String = replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
    }
}
