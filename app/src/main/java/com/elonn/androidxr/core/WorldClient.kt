package com.elonn.androidxr.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val TAG = "ElonnWorldClient"

class WorldAuthRequiredException : Exception()
class WorldUnavailableException(message: String) : Exception(message)

/**
 * A single POST /world/call request. Mirrors xreal.elonn.app's
 * WorldCallRequest -- operation + free-text input, plus the optional pieces
 * World's Call envelope carries: an action's operation_invocation to dispatch,
 * the opened/selected Object a call is scoped to (originObject), and the
 * currently-selected Object id, which World reads as "open this Finding"
 * (context.focus.object_id).
 */
data class WorldCallRequest(
    val operation: String,
    val inputText: String = "",
    val operationInvocation: JSONObject? = null,
    val originObject: String? = null,
    val selectedObjectId: String? = null,
)

/**
 * Talks to exactly one endpoint: POST /world/call. World has no other routes.
 * Mirrors xreal.elonn.app's WorldClient.cs -- same Call envelope shape, same
 * headers. This Runtime's capabilities are reported honestly: it is a plain
 * 2D Android screen right now, not a spatial one, so every XR-only capability
 * is false until the actual Jetpack XR scene work lands.
 */
class WorldClient(private val http: OkHttpClient = OkHttpClient()) {

    suspend fun call(token: String, datasetId: String?, request: WorldCallRequest): JSONObject {
        val envelope = JSONObject().apply {
            put("id", "call:runtime:${RuntimeConfig.RUNTIME_ID}:${System.currentTimeMillis()}")
            put("content", JSONObject().apply {
                put("operation", request.operation)
                put("input", JSONObject().apply {
                    put("type", "text")
                    put("text", request.inputText)
                })
                if (request.operationInvocation != null) {
                    put("operation_invocation", request.operationInvocation)
                }
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
                    if (!request.originObject.isNullOrBlank()) {
                        put("origin_object", request.originObject)
                    }
                })
                put("focus", JSONObject().apply {
                    if (!request.selectedObjectId.isNullOrBlank()) {
                        put("object_id", request.selectedObjectId)
                    }
                })
            })
        }

        Log.d(TAG, "-> ${request.operation} (origin=${request.originObject}, selected=${request.selectedObjectId})")
        val body = envelope.toString().toRequestBody("application/json".toMediaType())
        val httpRequest = Request.Builder()
            .url("${RuntimeConfig.WORLD_BASE_URL}/world/call")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .header("Cookie", "elonn_api_token=$token")
            .post(body)
            .build()

        return withContext(Dispatchers.IO) {
            try {
                http.newCall(httpRequest).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    Log.d(TAG, "<- ${response.code} for ${request.operation} (${text.length} bytes)")
                    if (response.code == 401) throw WorldAuthRequiredException()
                    if (!response.isSuccessful) {
                        throw WorldUnavailableException("World request failed: ${response.code} $text")
                    }
                    if (text.isBlank()) JSONObject() else JSONObject(text)
                }
            } catch (e: Exception) {
                Log.e(TAG, "call failed for ${request.operation}", e)
                throw e
            }
        }
    }
}
