package com.example.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedEnvelope(
    val ciphertextBase64: String,
    val ivBase64: String
)

data class E2eeDocumentSecret(
    val title: String,
    val summary: String,
    val extractedText: String,
    val thumbnailBase64: String
)

/**
 * End-to-End Encryption manager for private folders.
 * - Derives a deterministic 256-bit AES key from the user's Vault Passphrase + synced salt via PBKDF2WithHmacSHA256
 *   so private folders remain decryptable across all the user's devices.
 * - Encrypts metadata, OCR text, thumbnails, and off-device blobs using AES/GCM/NoPadding (128-bit auth tag).
 * - Optionally wraps the derived key with an Android Keystore key locally for seamless biometric unlock.
 */
object VaultCryptoManager {
    private const val PBKDF2_ALGO = "PBKDF2WithHmacSHA256"
    private const val PBKDF2_ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12
    private const val VERIFIER_PLAINTEXT = "DOCVAULT_E2EE_VERIFIER_V1"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEYSTORE_WRAP_ALIAS = "docvault_e2ee_keywrap_v1"

    private val secureRandom = SecureRandom()

    fun generateSaltBase64(): String {
        val salt = ByteArray(32)
        secureRandom.nextBytes(salt)
        return Base64.encodeToString(salt, Base64.NO_WRAP)
    }

    fun deriveKey(passphrase: CharArray, saltBase64: String): SecretKey {
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGO)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    fun createVerifier(key: SecretKey): String {
        val env = encryptBytes(VERIFIER_PLAINTEXT.toByteArray(Charsets.UTF_8), key)
        return "${env.ivBase64}:${env.ciphertextBase64}"
    }

    fun verifyKey(key: SecretKey, verifierString: String): Boolean {
        return try {
            val parts = verifierString.split(":")
            if (parts.size != 2) return false
            val plain = decryptBytes(parts[1], parts[0], key)
            String(plain, Charsets.UTF_8) == VERIFIER_PLAINTEXT
        } catch (_: Exception) {
            false
        }
    }

    fun keyFingerprint(key: SecretKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        return digest.take(6).joinToString(":") { "%02X".format(it) }
    }

    fun encryptBytes(plaintext: ByteArray, key: SecretKey): EncryptedEnvelope {
        val iv = ByteArray(GCM_IV_BYTES)
        secureRandom.nextBytes(iv)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedEnvelope(
            ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        )
    }

    fun decryptBytes(ciphertextBase64: String, ivBase64: String, key: SecretKey): ByteArray {
        val ciphertext = Base64.decode(ciphertextBase64, Base64.NO_WRAP)
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    fun encryptDocumentSecret(secret: E2eeDocumentSecret, key: SecretKey): EncryptedEnvelope {
        val json = JSONObject().apply {
            put("title", secret.title)
            put("summary", secret.summary)
            put("extractedText", secret.extractedText)
            put("thumbnailBase64", secret.thumbnailBase64)
        }.toString()
        return encryptBytes(json.toByteArray(Charsets.UTF_8), key)
    }

    fun decryptDocumentSecret(
        encryptedPayload: String,
        ivBase64: String,
        key: SecretKey
    ): E2eeDocumentSecret? {
        return try {
            val bytes = decryptBytes(encryptedPayload, ivBase64, key)
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            E2eeDocumentSecret(
                title = json.optString("title", "Private Document"),
                summary = json.optString("summary", ""),
                extractedText = json.optString("extractedText", ""),
                thumbnailBase64 = json.optString("thumbnailBase64", "")
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Wraps the user's E2EE vault key with a hardware-backed Android Keystore key
     * so biometric authentication can restore the key without re-entering the passphrase.
     */
    fun wrapKeyForDevice(vaultKey: SecretKey): EncryptedEnvelope? {
        return try {
            val ksKey = getOrCreateKeystoreWrapKey()
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, ksKey)
            val encrypted = cipher.doFinal(vaultKey.encoded)
            EncryptedEnvelope(
                ciphertextBase64 = Base64.encodeToString(encrypted, Base64.NO_WRAP),
                ivBase64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            )
        } catch (_: Exception) {
            null
        }
    }

    fun unwrapKeyFromDevice(ciphertextBase64: String, ivBase64: String): SecretKey? {
        return try {
            val ksKey = getOrCreateKeystoreWrapKey()
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val ciphertext = Base64.decode(ciphertextBase64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, ksKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            val rawBytes = cipher.doFinal(ciphertext)
            SecretKeySpec(rawBytes, "AES")
        } catch (_: Exception) {
            null
        }
    }

    private fun getOrCreateKeystoreWrapKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(KEYSTORE_WRAP_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_WRAP_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
