package com.personal.assistant.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.SecretKeyFactory

/**
 * Field-level encryption for the text the user would least like to leak: memories and chat messages.
 *
 * Why fields rather than the whole database file: whole-file encryption means SQLCipher, which is a
 * native dependency with its own licensing terms and a measurable cost on low-end devices. The keyed
 * fields here cover the free-text content, the key never leaves the Android Keystore (so it is not in
 * the APK, not in shared preferences, and not recoverable from a copied database file), and the
 * structured columns that remain readable -- dates, statuses, durations -- are what the app has to
 * query on anyway.
 *
 * The [PLAINTEXT_PREFIX] / [CIPHERTEXT_PREFIX] markers let the setting be turned off and on without a
 * migration: every value records how it was written, so a mixed table still reads correctly.
 */
class CryptoManager(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) {

    private val keyStore: KeyStore? = runCatching {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }.getOrNull()

    /** False on a device where the Keystore is unavailable; the app then stores plaintext and says so. */
    val isAvailable: Boolean get() = keyStore != null

    fun encrypt(plaintext: String): String {
        val key = secretKey() ?: return PLAINTEXT_PREFIX + plaintext
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            CIPHERTEXT_PREFIX + encode(iv) + SEPARATOR + encode(body)
        }.getOrElse { error ->
            // Never lose the user's text because encryption failed; record it readable and carry on.
            Log.w(TAG, "Encryption unavailable, storing value unencrypted", error)
            PLAINTEXT_PREFIX + plaintext
        }
    }

    fun decrypt(stored: String): String = when {
        stored.startsWith(PLAINTEXT_PREFIX) -> stored.removePrefix(PLAINTEXT_PREFIX)

        stored.startsWith(CIPHERTEXT_PREFIX) -> {
            val payload = stored.removePrefix(CIPHERTEXT_PREFIX)
            val parts = payload.split(SEPARATOR)
            if (parts.size != 2) {
                UNREADABLE
            } else {
                runCatching {
                    val key = secretKey() ?: return@runCatching UNREADABLE
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, decode(parts[0])))
                    String(cipher.doFinal(decode(parts[1])), Charsets.UTF_8)
                }.getOrElse {
                    // A key invalidated by a screen-lock change is the usual cause. The row survives as
                    // unreadable rather than crashing the screen that loaded it.
                    UNREADABLE
                }
            }
        }

        // Written before the prefixes existed, or by an import that carried plain values.
        else -> stored
    }

    private fun secretKey(): SecretKey? {
        val store = keyStore ?: return null
        return runCatching {
            (store.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generateKey()
        }.getOrNull()
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // Not tied to user authentication: alarms fire and notifications are built while the
                // device is locked, and they need the task title.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    // ------------------------------------------------------------------- backups

    /**
     * Encrypts an export with a passphrase the user supplies, so the backup file is useful off-device
     * without the Keystore key travelling with it.
     *
     * The salt and iteration count are written into the envelope, so a future build can raise the
     * iteration count without making existing backups unreadable.
     */
    fun encryptBackup(plaintext: String, passphrase: CharArray): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt, PBKDF2_ITERATIONS)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return listOf(
            BACKUP_MAGIC,
            PBKDF2_ITERATIONS.toString(),
            encode(salt),
            encode(cipher.iv),
            encode(body),
        ).joinToString(SEPARATOR)
    }

    /** Returns null for a wrong passphrase or a corrupt file, which the caller reports as such. */
    fun decryptBackup(envelope: String, passphrase: CharArray): String? {
        val parts = envelope.trim().split(SEPARATOR)
        if (parts.size != 5 || parts[0] != BACKUP_MAGIC) return null
        val iterations = parts[1].toIntOrNull() ?: return null
        if (iterations !in 1_000..1_000_000) return null
        return runCatching {
            val key = deriveKey(passphrase, decode(parts[2]), iterations)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, decode(parts[3])))
            String(cipher.doFinal(decode(parts[4])), Charsets.UTF_8)
        }.getOrNull()
    }

    fun isEncryptedBackup(text: String): Boolean = text.trimStart().startsWith(BACKUP_MAGIC)

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_SIZE_BITS)
        return SecretKeySpec(factory.generateSecret(spec).encoded, KeyProperties.KEY_ALGORITHM_AES)
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

    companion object {
        const val DEFAULT_KEY_ALIAS = "assistant_field_key"
        const val UNREADABLE = "[unreadable - encryption key changed]"

        private const val TAG = "CryptoManager"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val TAG_LENGTH_BITS = 128
        private const val KEY_SIZE_BITS = 256
        private const val SALT_BYTES = 16
        private const val PBKDF2_ITERATIONS = 200_000
        private const val SEPARATOR = ":"
        private const val PLAINTEXT_PREFIX = "p0."
        private const val CIPHERTEXT_PREFIX = "e1."
        private const val BACKUP_MAGIC = "ASSISTBAK1"
    }
}
