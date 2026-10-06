package com.elonn.androidxr

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.xr.arcore.Anchor
import androidx.xr.arcore.AnchorCreateSuccess
import androidx.xr.arcore.ArDevice
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.IntSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.AnchorSpace
import androidx.xr.scenecore.PanelEntity
import androidx.xr.scenecore.scene
import com.elonn.androidxr.core.Geo
import com.elonn.androidxr.core.WorldObject
import com.elonn.androidxr.core.createXrSession
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * Field: real ARCore-for-Jetpack-XR markers, placed via GPS bearing/distance exactly as the old
 * classic-ARCore renderer did (Geo.kt/FieldCamera.kt, both unchanged), but rendered through the
 * real Android XR spatial model instead of a hand-rolled GLSurfaceView/OpenGL camera-passthrough
 * renderer:
 *
 *   Session (createXrSession, DeviceTrackingMode.SPATIAL) -> real-world Pose computed from GPS
 *   bearing/distance -> Anchor.create -> AnchorSpace.create -> PanelEntity.create(parent =
 *   anchorSpace), with Full Space requested (Session.scene.requestFullSpace()) so spatial content
 *   actually composites at all -- Home Space confines an app to a single flat 2D panel and
 *   renders no spatial content whatsoever, confirmed live (entities created with zero errors,
 *   nothing appeared, until Full Space was requested).
 *
 * No GLSurfaceView, no manual OpenGL, no per-frame Session.update() call anywhere here --
 * SceneCore owns rendering. See ArCoreCameraBridge.kt's createXrSession doc for why that
 * specifically matters (the prior Jetpack XR attempt crashed on-device from exactly that kind of
 * app-owned render loop).
 *
 * Markers are reconciled (not placed once): whenever the Field object set or the member's
 * location changes, new objects with a real [WorldObject.location] get a real Anchor + a real
 * PanelEntity rendering [markerContent]; objects no longer wanted are detached/disposed. Anchor
 * placement retries rather than assuming the first attempt succeeds: the device/head tracking
 * system frequently hasn't produced a valid tracked pose/timestamp yet in the moment right after
 * session creation (confirmed live via a real crash, XR_ERROR_TIME_INVALID /
 * AnchorRuntimeFailureException: "(time == 0) is not a valid time") -- the exact same race the
 * old renderer's reconcileAnchors doc already documented and solved by retrying, same lesson,
 * different API.
 */
@Composable
fun ArCoreField(
    fieldObjects: List<WorldObject>,
    modifier: Modifier = Modifier,
    markerContent: @Composable (obj: WorldObject, distanceMeters: Double, modifier: Modifier) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity

    var permissionGranted by remember { mutableStateOf(hasFieldPermissions(context)) }
    val permissionLauncher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
        ) { _ -> permissionGranted = hasFieldPermissions(context) }
    val requiredPermissions =
        arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            // Not yet exposed as an android.Manifest.permission constant at this compileSdk --
            // confirmed via a real compiler error, not a guess. Literal string per
            // developer.android.com/develop/xr/jetpack-xr-sdk/arcore/anchors.
            "android.permission.SCENE_UNDERSTANDING_COARSE",
        )
    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(requiredPermissions)
    }

    if (!permissionGranted) {
        Box(modifier = modifier) {
            Column(modifier = Modifier.align(Alignment.Center).padding(24.dp)) {
                Text(
                    "Field needs camera, location, and scene access to place markers around you.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { permissionLauncher.launch(requiredPermissions) },
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("Grant access") }
            }
        }
        return
    }

    var session by remember { mutableStateOf<Session?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val created = createXrSession(activity)
        if (created == null) {
            error = "Could not create an XR session on this device/emulator."
        } else {
            // Home Space confines an app to a single flat 2D panel -- spatial content
            // (PanelEntity/AnchorSpace) never renders at all until Full Space is requested,
            // confirmed live: entity creation succeeded with zero errors, but nothing appeared,
            // until this call was added. Requires the manifest's
            // PROPERTY_XR_ACTIVITY_START_MODE=XR_ACTIVITY_START_MODE_FULL_SPACE_MANAGED
            // (AndroidManifest.xml) to be allowed to call this itself.
            created.scene.requestFullSpace()
            session = created
        }
    }

    val location by rememberDeviceLocation()
    val headingDegrees by rememberDeviceHeading()
    val markersByObjectId = remember { mutableMapOf<String, PlacedMarker>() }

    // The only place GPS/compass math runs: whenever the Field object set or the member's
    // location changes, reconcile which objects have a real Anchor+panel yet. Existing markers
    // are left completely untouched -- their pose is the Anchor's own tracked pose from here on.
    LaunchedEffect(session, fieldObjects, location?.latitude, location?.longitude) {
        val currentSession = session ?: return@LaunchedEffect
        val fix = location ?: return@LaunchedEffect

        val withLocation = fieldObjects.filter { it.location != null }
        val wantedIds = withLocation.map { it.id }.toSet()
        android.util.Log.d(
            "ElonnField",
            "reconcile: fieldObjects=${fieldObjects.size} withLocation=${withLocation.size} " +
                "already-placed=${markersByObjectId.keys} fix=(${fix.latitude},${fix.longitude})",
        )

        val staleIds = markersByObjectId.keys - wantedIds
        for (id in staleIds) {
            markersByObjectId.remove(id)?.dispose()
        }

        for (obj in withLocation) {
            if (markersByObjectId.containsKey(obj.id)) continue
            val loc = obj.location ?: continue
            val bearing = Geo.bearingDegrees(fix.latitude, fix.longitude, loc.first, loc.second)
            val distance = Geo.distanceMeters(fix.latitude, fix.longitude, loc.first, loc.second)
            android.util.Log.d(
                "ElonnField",
                "placing marker for ${obj.id} (${obj.title}) at (${loc.first},${loc.second}) bearing=$bearing distance=$distance",
            )
            val marker = placeMarker(currentSession, activity, obj, distance, bearing, headingDegrees, markerContent)
            if (marker != null) {
                markersByObjectId[obj.id] = marker
                android.util.Log.d("ElonnField", "marker placed for ${obj.id}")
            } else {
                android.util.Log.d("ElonnField", "marker placement FAILED for ${obj.id} after retries")
            }
        }
    }

    Box(modifier = modifier) {
        when {
            error != null -> Text(error ?: "", modifier = Modifier.padding(16.dp))
            session == null -> Text("Starting XR session...", modifier = Modifier.padding(16.dp))
            location == null -> Text("Waiting for a GPS fix...", modifier = Modifier.padding(16.dp))
        }
    }
}

/**
 * Radial distance, in meters, at which every Field marker is presented in SceneCore. A Runtime
 * presentation choice, not canonical data: Field placements carry a geographic location and
 * no distance unit (dev.elonn.local canonical placement/field docs).
 */
private const val FieldPresentationRadiusMeters = 3.0f

private class PlacedMarker(private val anchor: Anchor, private val panel: PanelEntity) {
    fun dispose() {
        // Entity.dispose() is deprecated -- entities are reclaimed automatically once detached
        // from the scene graph via parent = null, confirmed via a real compiler warning.
        panel.parent = null
        anchor.detach()
    }
}

/**
 * Places one real Anchor + PanelEntity for a single Field object, at a Pose computed once from
 * the device's current forward direction rotated by (bearing - headingDegrees) and scaled by
 * FieldPresentationRadiusMeters -- the same bearing calibration xreal.elonn.app's ArFieldRenderer.cs and the old
 * classic-ARCore renderer both use: establish which direction in the tracking space's local
 * coordinates corresponds to true north (by rotating the device's own current forward direction
 * back by the live compass heading), then rotate further by the object's real-world bearing.
 * The radial distance is FieldPresentationRadiusMeters, not the real distance: bearing is
 * geographically true, radius is presentation (see that constant).
 *
 * Retries up to 20 times (300ms apart): the device/head tracking system frequently hasn't
 * produced a valid tracked pose/timestamp yet in the moment right after session creation --
 * confirmed live via a real crash (XR_ERROR_TIME_INVALID / AnchorRuntimeFailureException).
 */
private suspend fun placeMarker(
    session: Session,
    activity: ComponentActivity,
    obj: WorldObject,
    distanceMeters: Double,
    bearingDegrees: Double,
    headingDegrees: Double,
    markerContent: @Composable (obj: WorldObject, distanceMeters: Double, modifier: Modifier) -> Unit,
): PlacedMarker? {
    var attempts = 0
    while (attempts < 20) {
        attempts++
        try {
            val devicePose = ArDevice.getInstance(session).state.value.devicePose
            val forward = devicePose.rotation * Vector3(0f, 0f, -1f)

            val relativeRadians = Math.toRadians(bearingDegrees - headingDegrees)
            val cosA = cos(relativeRadians).toFloat()
            val sinA = sin(relativeRadians).toFloat()
            // Same rotation-sign convention as the old renderer's placeAnchor: real-world
            // bearing increases clockwise viewed from above, so a positive bearing offset must
            // rotate forward's -Z component toward +X, not -X -- a naively-ported left-handed
            // rotation sign put a "15 right" test marker far to the left instead, caught live
            // on-device in the classic-ARCore renderer this replaces.
            val rotatedX = forward.x * cosA - forward.z * sinA
            val rotatedZ = forward.x * sinA + forward.z * cosA

            // Geographic distance is data; the radial position is presentation. Direction stays
            // true to the member's bearing, but the marker sits at a fixed comfortable radius so
            // distant objects (1,000+ km) remain visible in Field instead of landing far beyond
            // the view. The true distance still reaches markerContent via distanceMeters.
            val distance = FieldPresentationRadiusMeters
            val anchorPosition =
                Vector3(
                    devicePose.translation.x + rotatedX * distance,
                    devicePose.translation.y,
                    devicePose.translation.z + rotatedZ * distance,
                )
            val anchorPose = Pose(anchorPosition, Quaternion.fromEulerAngles(0f, 0f, 0f))

            // Anchor.create returns a sealed AnchorResult (AnchorCreateSuccess /
            // AnchorCreateResourcesExhausted / AnchorCreateTrackingUnavailable) for some failure
            // modes, but can also throw (e.g. AnchorRuntimeFailureException) for others --
            // confirmed via javap against the real arcore-1.0.0-rc01.aar and a real on-device
            // crash, not guessed.
            when (val result = Anchor.create(session, anchorPose)) {
                is AnchorCreateSuccess -> {
                    android.util.Log.d("ElonnField", "Anchor.create succeeded for ${obj.id} after $attempts attempt(s)")
                    val anchor = result.anchor
                    val anchorSpace = AnchorSpace.create(session, anchor)
                    val composeView =
                        ComposeView(activity).apply {
                            // PanelEntity hosts this View outside the ordinary Activity view
                            // hierarchy, so it never inherits a ViewTreeLifecycleOwner the way a
                            // normal activity-attached view would -- confirmed via a real crash
                            // (IllegalStateException: ViewTreeLifecycleOwner not found) without
                            // this. The Activity itself is a LifecycleOwner/ViewModelStoreOwner/
                            // SavedStateRegistryOwner, so it can serve as all three directly.
                            setViewTreeLifecycleOwner(activity)
                            setViewTreeViewModelStoreOwner(activity)
                            setViewTreeSavedStateRegistryOwner(activity)
                            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                            setContent { markerContent(obj, distanceMeters, Modifier) }
                        }
                    val panel =
                        PanelEntity.create(
                            session = session,
                            view = composeView,
                            pixelDimensions = IntSize2d(300, 160),
                            name = "field-marker-${obj.id}",
                            pose = Pose(Vector3(0f, 0f, 0f), Quaternion.fromEulerAngles(0f, 0f, 0f)),
                            parent = anchorSpace,
                        )
                    return PlacedMarker(anchor, panel)
                }
                else -> {
                    android.util.Log.d("ElonnField", "Anchor.create non-success for ${obj.id} attempt $attempts: $result")
                    delay(300)
                }
            }
        } catch (e: Exception) {
            android.util.Log.d("ElonnField", "Anchor.create threw for ${obj.id} attempt $attempts: ${e.javaClass.simpleName}: ${e.message}")
            delay(300)
        }
    }
    return null
}
