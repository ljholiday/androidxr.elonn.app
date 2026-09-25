package com.elonn.androidxr.core

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val PREFS_FILE = "elonn_session"
private const val KEY_ACCESS_TOKEN = "elonn_api_token"

/**
 * Persists the member's access token across process death, same role as
 * xreal.elonn.app's PlayerPrefs["elonn_api_token"] (see ElonnRuntimeApp.cs
 * StoreToken/ClearToken/platform.StoredToken). Android's real equivalent for
 * a bearer credential is EncryptedSharedPreferences, not a plaintext file --
 * PlayerPrefs there is also unencrypted, but this is the idiomatic secure
 * Android primitive for the same job, not a heavier abstraction.
 */
class TokenStore(context: Context) {
    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun store(token: String) {
        prefs.edit().putString(KEY_ACCESS_TOKEN, token).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_ACCESS_TOKEN).apply()
    }
}
