package com.elonn.androidxr.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
 * headers. The renderer and capabilities are reported as this build presents them:
 * a headset declares "headset" and the Field markers it renders, a phone declares
 * "phone" and only what the phone presentation renders.
 */
class WorldClient(
    // Declared to World on every Call. It must name the presentation this build actually renders
    // ("headset" or "phone"), so World composes content this device can show.
    private val renderer: String,
    // OkHttp's own default (10s connect/read/write) genuinely isn't enough -- a real
    // world.compose search ("cars") took just over 11s and got cut off by it live on-device.
    private val http: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build(),
) {

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
                    put("renderer", renderer)
                    put("capabilities", JSONObject().apply {
                        put("dock", false)
                        put("surface_stacks", false)
                        // Real Field markers (GPS-placed Anchor + PanelEntity via Jetpack XR,
                        // see ArCorePassthrough.kt) are actually implemented now -- this was
                        // correctly false while this Runtime was only a flat 2D screen with no
                        // Field rendering at all, but leaving it false now under-reports real
                        // capability and likely causes World to omit Field placements entirely.
                        put("field_markers", true)
                        put("pointer_input", true)
                        put("stereo_rendering", false)
                        put("headset_mode", renderer == "headset")
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
