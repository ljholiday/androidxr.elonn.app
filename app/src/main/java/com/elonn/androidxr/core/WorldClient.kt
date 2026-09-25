package com.elonn.androidxr.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class WorldAuthRequiredException : Exception()
class WorldUnavailableException(message: String) : Exception(message)

/**
 * Talks to exactly one endpoint: POST /world/call. World has no other routes.
 * Mirrors xreal.elonn.app's WorldClient.cs -- same Call envelope shape, same
 * headers. This Runtime's capabilities are reported honestly: it is a plain
 * 2D Android screen right now, not a spatial one, so every XR-only capability
 * is false until the actual Jetpack XR scene work lands.
 */
class WorldClient(private val http: OkHttpClient = OkHttpClient()) {

    suspend fun call(token: String, operation: String, datasetId: String?): JSONObject {
        val envelope = JSONObject().apply {
            put("id", "call:runtime:${RuntimeConfig.RUNTIME_ID}:${System.currentTimeMillis()}")
            put("content", JSONObject().apply {
                put("operation", operation)
                put("input", JSONObject().apply {
                    put("type", "text")
                    put("text", "")
                })
            })
            put("context", JSONObject().apply {
                put("runtime", JSONObject().apply {
                    put("id", RuntimeConfig.RUNTIME_ID)
                    put("version", RuntimeConfig.VERSION)
                    put("renderer", "phone")
                    put("capabilities", JSONObject().apply {
                        put("dock", false)
                        put("surface_stacks", false)
                        put("field_markers", false)
                        put("pointer_input", true)
                        put("stereo_rendering", false)
                        put("headset_mode", false)
                        put("spatial_ui", false)
                        put("hand_input", false)
                    })
                })
                put("scope", "default")
                put("runtime_state", JSONObject().apply {
                    put("dataset_id", datasetId ?: JSONObject.NULL)
                })
                put("focus", JSONObject())
            })
        }

        val body = envelope.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${RuntimeConfig.WORLD_BASE_URL}/world/call")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .header("Cookie", "elonn_api_token=$token")
            .post(body)
            .build()

        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code == 401) throw WorldAuthRequiredException()
                if (!response.isSuccessful) {
                    throw WorldUnavailableException("World request failed: ${response.code} $text")
                }
                if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        }
    }
}
