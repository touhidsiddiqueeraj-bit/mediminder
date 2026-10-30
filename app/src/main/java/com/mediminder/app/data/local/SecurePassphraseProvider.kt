package com.mediminder.app.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Guards the SQLCipher database passphrase.
 *
 * - Passphrase: 32 random bytes, generated once.
 * - At rest: encrypted with an AES/GCM key held in the Android Keystore
 *   (the key never leaves secure hardware and is never written to disk).
 * - Storage: [PASSPHRASE_FILE] in app-private storage, format `iv + ciphertext`.
 *
 * If the Keystore key is ever lost (rare; typically after an uninstall that
 * kept app data), the passphrase is unrecoverable — we regenerate and remove
 * the unreadable database rather than crash-looping. This is the documented
 * trade-off of full-disk-style encryption without user credentials.
 */
@Singleton
class SecurePassphraseProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile
    private var cached: ByteArray? = null

    fun getOrCreate(): ByteArray {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val file = File(context.filesDir, PASSPHRASE_FILE)
            val passphrase = if (file.exists()) {
                runCatching { decrypt(file.readBytes()) }
                    .getOrElse {
                        removeUnreadableDatabase()
                        generateAndStore(file)
                    }
            } else {
                generateAndStore(file)
            }
            cached = passphrase
            return passphrase
        }
    }

    private fun generateAndStore(file: File): ByteArray {
        val passphrase = ByteArray(PASSPHRASE_LENGTH).also { SecureRandom().nextBytes(it) }
        file.writeBytes(encrypt(passphrase))
        return passphrase
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_LENGTH_BYTES) { "passphrase file too short" }
        val iv = blob.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = blob.copyOfRange(IV_LENGTH_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun removeUnreadableDatabase() {
        val dbs = listOf(
            File(context.filesDir.parentFile, "databases/${MediMinderDatabase.NAME}"),
        )
        dbs.forEach { dbFile ->
            dbFile.parentFile?.listFiles()?.forEach { file ->
                if (file.name.startsWith(MediMinderDatabase.NAME)) file.delete()
            }
            dbFile.delete()
        }
    }

    private companion object {
        const val PASSPHRASE_FILE = ".dbkey"
        const val PASSPHRASE_LENGTH = 32
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "mediminder_master_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH_BYTES = 12
        const val TAG_LENGTH_BITS = 128

        // Base64 is unused for storage but kept for potential future export:
        @Suppress("unused")
        fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
