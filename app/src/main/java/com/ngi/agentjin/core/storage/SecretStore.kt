package com.ngi.agentjin.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

/**
 * Device-local secret storage for:
 *  - optional biometric-wrapped copy of the derived memory key (never a substitute for the password)
 *  - future OAuth tokens for connector plugins (Android Keystore / EncryptedSharedPreferences)
 *
 * Nothing here is written to the portable SD-card folder.
 */
class SecretStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = devicePrefs(appContext, "agentjin_secrets")

    fun putToken(pluginName: String, tokenId: String, value: String) {
        prefs.edit().putString(tokenKey(pluginName, tokenId), value).apply()
    }

    fun getToken(pluginName: String, tokenId: String): String? {
        return prefs.getString(tokenKey(pluginName, tokenId), null)
    }

    fun deleteToken(pluginName: String, tokenId: String) {
        prefs.edit().remove(tokenKey(pluginName, tokenId)).apply()
    }

    fun deleteAllTokensFor(pluginName: String) {
        val prefix = "token:$pluginName:"
        val ed = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { ed.remove(it) }
        ed.apply()
    }

    /**
     * Cache the derived AES key, wrapped with a biometric-gated Keystore key.
     * The password is still required on a new device / after uninstall.
     */
    fun cacheDerivedKeyForBiometric(rawKey: ByteArray) {
        val ksKey = getOrCreateBiometricWrapKey()
        val cipher = Cipher.getInstance(WRAP_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, ksKey)
        val iv = cipher.iv
        val wrapped = cipher.doFinal(rawKey)
        prefs.edit()
            .putString(KEY_WRAP_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString(KEY_WRAP_BLOB, Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .apply()
    }

    fun hasBiometricWrappedKey(): Boolean {
        return prefs.contains(KEY_WRAP_BLOB) && prefs.contains(KEY_WRAP_IV)
    }

    fun createBiometricUnlockCipher(): Cipher {
        val ksKey = getOrCreateBiometricWrapKey()
        val ivB64 = prefs.getString(KEY_WRAP_IV, null)
            ?: throw IllegalStateException("no wrapped key")
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(WRAP_TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, ksKey, GCMParameterSpec(128, iv))
        return cipher
    }

    fun unwrapDerivedKey(unlockedCipher: Cipher): ByteArray {
        val blobB64 = prefs.getString(KEY_WRAP_BLOB, null)
            ?: throw IllegalStateException("no wrapped key")
        val blob = Base64.decode(blobB64, Base64.NO_WRAP)
        return unlockedCipher.doFinal(blob)
    }

    fun clearBiometricCache() {
        prefs.edit().remove(KEY_WRAP_IV).remove(KEY_WRAP_BLOB).apply()
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(BIOMETRIC_ALIAS)) ks.deleteEntry(BIOMETRIC_ALIAS)
        } catch (_: Exception) {
        }
    }

    private fun getOrCreateBiometricWrapKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(BIOMETRIC_ALIAS)) {
            val entry = ks.getEntry(BIOMETRIC_ALIAS, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            BIOMETRIC_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .setKeySize(256)
            .build()
        gen.init(spec)
        return gen.generateKey()
    }

    private fun tokenKey(plugin: String, id: String) = "token:$plugin:$id"

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val BIOMETRIC_ALIAS = "agentjin_biometric_wrap"
        private const val WRAP_TRANSFORM = "AES/GCM/NoPadding"
        private const val KEY_WRAP_IV = "bio_wrap_iv"
        private const val KEY_WRAP_BLOB = "bio_wrap_blob"
    }
}
