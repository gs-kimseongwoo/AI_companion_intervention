package com.example.zetareader

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** TypeSafe API 키를 APK가 아니라 이 기기의 Android Keystore로 보호해 저장한다. */
internal object JevApiKeyStore {
    private const val KEY_ALIAS = "zeta_reader_typesafe_api_key"
    private const val PREFERENCES = "jev_credentials"
    private const val CIPHERTEXT = "api_key_ciphertext"
    private const val INITIALIZATION_VECTOR = "api_key_iv"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun isConfigured(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .contains(CIPHERTEXT)

    fun save(context: Context, rawApiKey: String) {
        val apiKey = rawApiKey.trim()
        require(apiKey.isNotEmpty()) { "TypeSafe API 키를 입력하세요." }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(
                INITIALIZATION_VECTOR,
                Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            )
            .apply()
    }

    fun load(context: Context): String? = runCatching {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val encrypted = preferences.getString(CIPHERTEXT, null) ?: return null
        val initializationVector = preferences.getString(INITIALIZATION_VECTOR, null)
            ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(
                128,
                Base64.decode(initializationVector, Base64.NO_WRAP),
            ),
        )
        cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
            .toString(Charsets.UTF_8)
    }.getOrNull()

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }
}
