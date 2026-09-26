package com.elonn.androidxr

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.elonn.androidxr.core.ArCoreCameraBridge
import com.elonn.androidxr.core.Geo
import com.elonn.androidxr.core.WorldObject
import com.google.ar.core.Anchor
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * Field: a real camera passthrough with real ARCore-tracked markers. Per the member's explicit
 * direction: World provides geographic placement (lat/lon, via [WorldObject.location]); ARCore
 * owns localization and tracked spatial pose from there. GPS and compass (Geo.kt,
 * rememberDeviceHeading) are used exactly once per Field object, when [ArFieldRenderer.placeAnchor]
 * creates its real local ARCore Anchor -- never again after that. Every frame reads each Anchor's
 * own live-tracked Pose and projects it with the camera's real view/projection matrices; there is
 * no device pitch/yaw/roll compensation or per-frame geographic screen-position calculation
 * anywhere in this file.
 *
 * See ArCoreCameraBridge.kt for why this owns a plain ARCore Session directly (no Jetpack XR
 * Session, no Geospatial, no Google Cloud dependency of any kind) -- Session.update() runs on this
 * composable's own GLSurfaceView thread, the standard single-threaded ARCore integration pattern,
 * which is also what makes the classic BIND_TO_TEXTURE_EXTERNAL_OES camera path work with no
 * native code.
 */
@Composable
fun ArCoreField(
    fieldObjects: List<WorldObject>,
    modifier: Modifier = Modifier,
    markerContent: @Composable (obj: WorldObject, distanceMeters: Double, modifier: Modifier) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity

    var permissionGranted by remember { mutableStateOf(hasCameraAndLocationPermission(context)) }
    val permissionLauncher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
        ) { _ -> permissionGranted = hasCameraAndLocationPermission(context) }
    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            permissionLauncher.launch(arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    if (!permissionGranted) {
        Box(modifier = modifier) {
            androidx.compose.foundation.layout.Column(modifier = Modifier.align(Alignment.Center).padding(24.dp)) {
                Text("Field needs camera and location access to place markers around you.", style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = {
                        permissionLauncher.launch(arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.ACCESS_FINE_LOCATION))
                    },
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("Grant access") }
            }
        }
        return
    }

    var bridge by remember { mutableStateOf<ArCoreCameraBridge?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var renderer by remember { mutableStateOf<ArFieldRenderer?>(null) }
    var markers by remember { mutableStateOf<List<ScreenMarker>>(emptyList()) }
    val location by rememberDeviceLocation()
    val headingDegrees by rememberDeviceHeading()

    DisposableEffect(Unit) {
        val created = ArCoreCameraBridge.create(activity)
        if (created == null) {
            error = "ARCore is not supported/installed on this device."
        } else {
            try {
                created.resume()
                bridge = created
            } catch (e: Exception) {
                error = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
        onDispose { bridge?.pause() }
    }

    // The only place GPS/compass math runs: whenever the Field object set or the member's
    // location changes, reconcile which objects have a real Anchor yet. Existing anchors are
    // left completely untouched -- their pose is ARCore's own from here on.
    val fix = location
    LaunchedEffect(renderer, fieldObjects, fix?.latitude, fix?.longitude) {
        val currentRenderer = renderer ?: return@LaunchedEffect
        val currentFix = fix ?: return@LaunchedEffect
        currentRenderer.syncAnchors(fieldObjects, currentFix.latitude, currentFix.longitude, headingDegrees)
    }

    Box(modifier = modifier) {
        when {
            error != null -> Text(error ?: "", modifier = Modifier.padding(16.dp))
            bridge == null -> Text("Starting ARCore session...", modifier = Modifier.padding(16.dp))
            else -> {
                val currentBridge = bridge!!
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        GLSurfaceView(ctx).apply {
                            setEGLContextClientVersion(2)
                            val newRenderer =
                                ArFieldRenderer(
                                    currentBridge,
                                    currentRotation = {
                                        @Suppress("DEPRECATION")
                                        (ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager)
                                            .defaultDisplay.rotation
                                    },
                                ) { updated ->
                                    Handler(Looper.getMainLooper()).post { markers = updated }
                                }
                            renderer = newRenderer
                            setRenderer(newRenderer)
                            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                        }
                    },
                )

                if (fix == null) {
                    Text(
                        "Waiting for a GPS fix...",
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                    )
                }

                for (marker in markers) {
                    val obj = fieldObjects.find { it.id == marker.objectId } ?: continue
                    val loc = obj.location
                    val distance =
                        if (fix != null && loc != null) {
                            Geo.distanceMeters(fix.latitude, fix.longitude, loc.first, loc.second)
                        } else {
                            0.0
                        }
                    markerContent(obj, distance, Modifier.align(Alignment.TopStart).pxOffset(marker.xPx, marker.yPx))
                }
            }
        }
    }
}

data class ScreenMarker(val objectId: String, val xPx: Float, val yPx: Float)

/** Centers the marker horizontally on (xPx, yPx) and sits it just above that point, like a pin. */
private fun Modifier.pxOffset(xPx: Float, yPx: Float): Modifier =
    this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.placeRelative((xPx - placeable.width / 2f).toInt(), (yPx - placeable.height).toInt())
        }
    }

/**
 * The camera background: the classic ARCore camera-background technique, using
 * BIND_TO_TEXTURE_EXTERNAL_OES (ArCoreCameraBridge.kt explains why this works here but not through
 * Jetpack XR's own Session). [ArCoreCameraBridge.update] is called from this renderer's own
 * onDrawFrame -- i.e. from this GLSurfaceView's own GL thread, which has the current EGL context
 * that mode needs.
 *
 * Anchor placement ([syncAnchors]/[placeAnchor]) uses GPS/compass exactly once per Field object,
 * to compute its initial Pose relative to the camera's local tracking origin at that moment. Every
 * subsequent frame reads each Anchor's own live-tracked Pose ([projectAnchorsToScreen]) and
 * projects it to screen space with the camera's real view/projection matrices for that exact frame
 * -- no bearing, pitch, yaw, or roll math after an anchor is placed.
 */
private class ArFieldRenderer(
    private val bridge: ArCoreCameraBridge,
    private val currentRotation: () -> Int,
    private val onMarkersUpdated: (List<ScreenMarker>) -> Unit,
) : GLSurfaceView.Renderer {
    private var programHandle = 0
    private var textureId = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var textureUniformHandle = 0
    private var viewportWidth = 0
    private var viewportHeight = 0

    @Volatile private var latestFrame: Frame? = null
    private val anchorsByObjectId = mutableMapOf<String, Anchor>()

    private data class SyncRequest(
        val fieldObjects: List<WorldObject>,
        val memberLat: Double,
        val memberLon: Double,
        val headingDegrees: Double,
    )

    // Written from Compose's LaunchedEffect (main thread) whenever the Field object set or the
    // member's location changes; read and acted on from the GL thread in reconcileAnchors, which
    // runs every frame -- see that function's doc for why a single Compose-triggered attempt isn't
    // enough on its own.
    @Volatile private var pendingSync: SyncRequest? = null

    private val quadCoords = floatArrayOf(-1f, -1f, +1f, -1f, -1f, +1f, +1f, +1f)
    private var quadTexCoords = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
    private val quadVertexBuffer = directFloatBuffer(quadCoords)
    private var quadTexCoordBuffer = directFloatBuffer(quadTexCoords)

    /** Records the desired Field object set for [reconcileAnchors] to act on -- see its doc. */
    fun syncAnchors(fieldObjects: List<WorldObject>, memberLat: Double, memberLon: Double, headingDegrees: Double) {
        pendingSync = SyncRequest(fieldObjects, memberLat, memberLon, headingDegrees)
    }

    /**
     * Reconciles which Field objects have a real Anchor yet, using the most recent [syncAnchors]
     * request. Anchors for objects no longer wanted are detached; new objects with a real
     * [WorldObject.location] get a real ARCore Anchor via [placeAnchor] -- which requires
     * [TrackingState.TRACKING] and silently declines otherwise (see its doc). Called every frame,
     * from the GL thread, rather than once when [syncAnchors] is called: ARCore frequently hasn't
     * reached TRACKING yet in the first frame or two after the session starts (real bug hit live --
     * two real, correctly-located Field objects never got a marker because the one-shot
     * Compose-triggered placement attempt raced ARCore's own tracking startup and lost, with
     * nothing ever retrying). Anchors already placed are skipped cheaply (a map lookup) every
     * frame; this is not a recurring geographic calculation for already-placed anchors, only a
     * retry path for ones still waiting on tracking to start.
     */
    private fun reconcileAnchors() {
        val request = pendingSync ?: return
        val withLocation = request.fieldObjects.filter { it.location != null }
        val wantedIds = withLocation.map { it.id }.toSet()
        if (wantedIds == anchorsByObjectId.keys) return

        val staleIds = anchorsByObjectId.keys - wantedIds
        for (id in staleIds) {
            anchorsByObjectId.remove(id)?.detach()
        }

        for (obj in withLocation) {
            if (anchorsByObjectId.containsKey(obj.id)) continue
            val loc = obj.location ?: continue
            val bearing = Geo.bearingDegrees(request.memberLat, request.memberLon, loc.first, loc.second)
            val distance = Geo.distanceMeters(request.memberLat, request.memberLon, loc.first, loc.second)
            val anchor = placeAnchor(bearing, distance, request.headingDegrees) ?: continue
            anchorsByObjectId[obj.id] = anchor
        }
    }

    /**
     * The one place GPS/compass math happens, for one Field object. [headingDegrees] is the
     * device's real compass heading right now (0 = true north); this establishes, once, which
     * direction in ARCore's local coordinate space corresponds to true north (by rotating the
     * camera's own current forward direction back by that heading) -- the same calibration
     * xreal.elonn.app's ArFieldRenderer.cs already does for the headset case. [bearing]'s further
     * rotation from that established north produces a fixed local Pose -- a real ARCore Anchor
     * from here on, never recomputed from heading again.
     */
    private fun placeAnchor(bearing: Double, distanceMeters: Double, headingDegrees: Double): Anchor? {
        val frame = latestFrame ?: return null
        if (frame.camera.trackingState != TrackingState.TRACKING) return null
        val cameraPose = frame.camera.pose

        val forward = cameraPose.transformPoint(floatArrayOf(0f, 0f, -1f))
        val origin = cameraPose.translation
        val forwardDir = floatArrayOf(forward[0] - origin[0], forward[1] - origin[1], forward[2] - origin[2])

        val relativeRadians = Math.toRadians(bearing - headingDegrees)
        val cosA = cos(relativeRadians).toFloat()
        val sinA = sin(relativeRadians).toFloat()
        // Rotate the camera's own forward direction around the local up (Y) axis -- ARCore's
        // world Y is gravity-aligned "up" by construction, so this is a plain 2D rotation in the
        // local X/Z ground plane. Real-world bearing increases clockwise viewed from above;
        // ARCore's coordinate space is right-handed with the camera looking down -Z and +X to its
        // right, so a positive bearing offset must rotate forward's -Z component toward +X, not
        // -X -- confirmed live on-device (a naively-ported left-handed rotation sign put a "15
        // right" test marker far to the left instead).
        val rotatedX = forwardDir[0] * cosA - forwardDir[2] * sinA
        val rotatedZ = forwardDir[0] * sinA + forwardDir[2] * cosA

        val distance = distanceMeters.toFloat()
        val anchorPosition =
            floatArrayOf(
                origin[0] + rotatedX * distance,
                origin[1],
                origin[2] + rotatedZ * distance,
            )
        val pose = Pose(anchorPosition, floatArrayOf(0f, 0f, 0f, 1f))
        return bridge.createAnchor(pose)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        bridge.setCameraTextureName(textureId)

        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_SRC)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SRC)
        programHandle =
            GLES20.glCreateProgram().also { program ->
                GLES20.glAttachShader(program, vertexShader)
                GLES20.glAttachShader(program, fragmentShader)
                GLES20.glLinkProgram(program)
            }
        positionHandle = GLES20.glGetAttribLocation(programHandle, "a_Position")
        texCoordHandle = GLES20.glGetAttribLocation(programHandle, "a_TexCoord")
        textureUniformHandle = GLES20.glGetUniformLocation(programHandle, "u_Texture")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        // The real current display rotation, not a hardcoded portrait assumption -- ARCore uses
        // this (plus width/height) to compute the camera-to-display transform in
        // transformCoordinates2d below. Without the real rotation here, the camera texture stays
        // mapped for whatever orientation the session started in, which is what produced the
        // "camera image rotated 90 degrees" bug when the device (and this GLSurfaceView, which is
        // recreated along with the rest of the Activity on a rotation, since it isn't declared to
        // handle orientation changes itself) rotated to landscape.
        bridge.session.setDisplayGeometry(currentRotation(), width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val frame =
            try {
                bridge.update()
            } catch (e: Exception) {
                return
            }
        latestFrame = frame
        reconcileAnchors()

        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoords,
            )
            quadTexCoordBuffer = directFloatBuffer(quadTexCoords)
        }

        GLES20.glUseProgram(programHandle)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureUniformHandle, 0)

        quadVertexBuffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)

        quadTexCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, quadTexCoordBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)

        projectAnchorsToScreen(frame)
    }

    /**
     * Every marker's position, every frame, comes from here: each Anchor's own live-tracked Pose
     * (ARCore's local VIO, not this Runtime) transformed by the camera's real view/projection
     * matrices for this exact frame. No heading, pitch, yaw, roll, bearing, or distance
     * recomputation happens anywhere in this function.
     */
    private fun projectAnchorsToScreen(frame: Frame) {
        if (anchorsByObjectId.isEmpty() || viewportWidth == 0 || viewportHeight == 0) {
            if (anchorsByObjectId.isEmpty()) onMarkersUpdated(emptyList())
            return
        }

        val viewMatrix = FloatArray(16)
        val projectionMatrix = FloatArray(16)
        frame.camera.getViewMatrix(viewMatrix, 0)
        frame.camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 1000f)
        val viewProjection = FloatArray(16)
        Matrix.multiplyMM(viewProjection, 0, projectionMatrix, 0, viewMatrix, 0)

        val screenMarkers = mutableListOf<ScreenMarker>()
        for ((objectId, anchor) in anchorsByObjectId) {
            if (anchor.trackingState != TrackingState.TRACKING) continue
            val worldPosition = anchor.pose.translation
            val clip = FloatArray(4)
            Matrix.multiplyMV(
                clip,
                0,
                viewProjection,
                0,
                floatArrayOf(worldPosition[0], worldPosition[1], worldPosition[2], 1f),
                0,
            )
            if (clip[3] <= 0f) continue // behind the camera
            val ndcX = clip[0] / clip[3]
            val ndcY = clip[1] / clip[3]
            val screenX = (ndcX * 0.5f + 0.5f) * viewportWidth
            val screenY = (1f - (ndcY * 0.5f + 0.5f)) * viewportHeight
            screenMarkers.add(ScreenMarker(objectId, screenX, screenY))
        }
        onMarkersUpdated(screenMarkers)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun directFloatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(values)
            position(0)
        }

    private companion object {
        const val VERTEX_SHADER_SRC =
            """
            attribute vec2 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = vec4(a_Position, 0.0, 1.0);
                v_TexCoord = a_TexCoord;
            }
            """

        const val FRAGMENT_SHADER_SRC =
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
            """
    }
}
