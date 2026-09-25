package com.elonn.androidxr.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * The pre-auth boundary. World and Conductor require an authenticated member,
 * so login/register cannot go through them -- api.elonn owns the auth-form
 * screen and serves it as an unauthenticated canonical Dataset. Mirrors
 * xreal.elonn.app's ApiAuthClient.cs and web.elonn.local's auth-client.js:
 * there is no hand-written login form here, only a generic Dataset fetch and
 * a submit of whatever fields that Dataset's action described.
 */
class AuthClient(private val http: OkHttpClient = OkHttpClient()) {

    suspend fun loadForm(mode: String): JSONObject {
        val query = if (mode == "register") "register" else "login"
        val request = Request.Builder()
            .url("${RuntimeConfig.API_BASE_URL}/identity/auth-form?mode=$query")
            .header("Accept", "application/json")
            .header("X-Elonn-Runtime", RuntimeConfig.RUNTIME_ID)
            .get()
            .build()
        return execute(request)
    }

    suspend fun submit(path: String, payload: JSONObject): JSONObject {
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(RuntimeConfig.API_BASE_URL + path)
            .header("Accept", "application/json")
            .header("X-Elonn-Runtime", RuntimeConfig.RUNTIME_ID)
            .post(body)
            .build()
        return execute(request)
    }

    suspend fun logout(token: String): JSONObject {
        val request = Request.Builder()
            .url("${RuntimeConfig.API_BASE_URL}/identity/logout")
            .header("Accept", "application/json")
            .header("X-Elonn-Runtime", RuntimeConfig.RUNTIME_ID)
            .header("Authorization", "Bearer $token")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        return execute(request)
    }

    private suspend fun execute(request: Request): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }
}
