package com.SyncCS

import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal object LocalVault {
    private const val KEY_ALIAS = "synccs_local_protection_v2"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build()
        )
        return generator.generateKey()
    }
    fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // AndroidKeyStore requires the provider to generate the IV for AES-GCM encryption.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        check(iv.size == 12) { "Unexpected AndroidKeyStore GCM nonce length" }
        val ciphertext = cipher.doFinal(plain)
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }
    fun decrypt(data: String): ByteArray {
        val bytes = Base64.decode(data, Base64.DEFAULT)
        require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
    }
}

internal class SyncCipher(private val key: ByteArray) {
    private val random = SecureRandom()
    fun encrypt(itemKey: String, data: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(itemKey.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + cipher.doFinal(data.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    fun decrypt(itemKey: String, payload: String): String {
        val bytes = Base64.decode(payload, Base64.DEFAULT)
        require(bytes.size >= 29)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(itemKey.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
    companion object {
        fun derive(password: String, url: String, userId: String): SyncCipher {
            require(password.length >= 12) { "Recovery passphrase must have at least 12 characters." }
            val salt = MessageDigest.getInstance("SHA-256")
                .digest(("SyncCS-v2|" + url + "|" + userId).toByteArray(Charsets.UTF_8))
            val spec = PBEKeySpec(password.toCharArray(), salt, 210_000, 256)
            val derived = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
            return SyncCipher(derived)
        }
    }
}

internal object GitHubDigest {
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
