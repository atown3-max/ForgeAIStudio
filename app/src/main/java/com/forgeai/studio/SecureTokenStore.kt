package com.forgeai.studio

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureTokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("forge_secure", Context.MODE_PRIVATE)

    fun save(token: String) = saveSecret("replicate", token)
    fun load(): String? = loadSecret("replicate")
    fun clear() = clearSecret("replicate")

    fun saveRunPod(token: String) = saveSecret("runpod", token)
    fun loadRunPod(): String? = loadSecret("runpod")
    fun clearRunPod() = clearSecret("runpod")

    private fun alias(name: String) = "forge_${name}_token_key"

    private fun key(name: String): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias(name), null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias(name),
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun saveSecret(name: String, token: String) {
        if (token.isBlank()) {
            clearSecret(name)
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(name))
        val encrypted = cipher.doFinal(token.trim().toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("${name}_ct", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("${name}_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    private fun loadSecret(name: String): String? {
        val hasCurrent = prefs.contains("${name}_ct") && prefs.contains("${name}_iv")
        val ctKey = if (hasCurrent) "${name}_ct" else if (name == "replicate") "token_ct" else "${name}_ct"
        val ivKey = if (hasCurrent) "${name}_iv" else if (name == "replicate") "token_iv" else "${name}_iv"
        val ct = prefs.getString(ctKey, null) ?: return null
        val iv = prefs.getString(ivKey, null) ?: return null
        return runCatching {
            val keyName = if (name == "replicate" && ctKey == "token_ct") "forge_replicate_token_key" else alias(name)
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val secret = (keyStore.getKey(keyName, null) as? SecretKey) ?: key(name)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun clearSecret(name: String) {
        val edit = prefs.edit().remove("${name}_ct").remove("${name}_iv")
        if (name == "replicate") edit.remove("token_ct").remove("token_iv")
        edit.apply()
    }
}
