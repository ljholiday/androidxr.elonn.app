package com.elonn.androidxr

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Surface
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
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.AnchorSpace
import androidx.xr.scenecore.PanelEntity
import androidx.xr.scenecore.Space
import androidx.xr.scenecore.scene
import com.elonn.androidxr.core.Geo
import com.elonn.androidxr.core.WorldObject
import com.elonn.androidxr.core.createXrSession
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
internal fun ArCoreField(
    fieldObjects: List<WorldObject>,
    modifier: Modifier = Modifier,
    markerContent: @Composable (obj: WorldObject, distanceMeters: Double, modifier: Modifier) -> Unit,
    carry: CarryInputs,
    carryContent: @Composable (CarryInputs) -> Unit,
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
    // The hosted Carry view reads these on each composition, so it always shows the current
    // Entry, results, and Carry state rather than the state from when its panel was created.
    val carryState = remember { mutableStateOf(carry) }
    SideEffect { carryState.value = carry }
    val carryPanelRef = remember { mutableStateOf<PanelEntity?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            carryPanelRef.value?.parent = null
        }
    }

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

    // Carry lives in its own panel, sized CarryPanelSizeMeters and hosted like the Field markers,
    // so it is not clipped by the system-sized Activity main panel. Each frame its pose is the
    // head-lock calculation: a point a fixed distance ahead of the device, with the device's
    // rotation. Field markers stay where their anchors put them and turn toward the member.
    LaunchedEffect(session) {
        val currentSession = session ?: return@LaunchedEffect
        // Detach any Carry panel from an earlier run first. This effect can run more than once for
        // the same session, and each run would otherwise leave a second Carry panel in the scene.
        // At most one Carry host per session. Remove the existing host explicitly before replacing it.
        carryPanelRef.value?.let { previous ->
            previous.parent = null
        }
        val carryView =
            ComposeView(activity).apply {
                setViewTreeLifecycleOwner(activity)
                setViewTreeViewModelStoreOwner(activity)
                setViewTreeSavedStateRegistryOwner(activity)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    ElonnTheme {
                        // A plain view has no Surface to set the content color, so text would
                        // fall back to black on the dark Carry windows.
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ) {
                            carryState.value?.let {
                                carryContent(it)
                            }
                        }
                    }
                }
            }
        val carryPanel =
            PanelEntity.create(
                currentSession,
                carryView,
                CarryPanelSizeMeters,
                "carry",
                Pose(Vector3(0f, 0f, 0f), Quaternion.fromEulerAngles(0f, 0f, 0f)),
                // The activity space is SceneCore's own default parent for panels. Without a
                // parent, the head-lock pose written in Space.REAL_WORLD throws at runtime.
                currentSession.scene.activitySpace,
            )
        carryPanelRef.value = carryPanel
        // Stops once a newer run has replaced this panel, so only the current panel is moved.
        while (carryPanelRef.value === carryPanel) {
            withFrameNanos {
                val device = ArDevice.getInstance(currentSession).state.value.devicePose
                val ahead = device.rotation * Vector3(0f, 0f, -CarryPanelDistanceMeters)
                val carryPosition =
                    Vector3(
                        device.translation.x + ahead.x,
                        device.translation.y + ahead.y,
                        device.translation.z + ahead.z,
                    )
                carryPanel.setPose(Pose(carryPosition, device.rotation), Space.REAL_WORLD)
                for (marker in markersByObjectId.values) marker.faceToward(device.translation)
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

/**
 * Distance of Carry's head-locked main panel from the viewer, in meters. A presentation choice
 * for the first implementation, fixed until a member-facing control exists.
 */
private const val CarryPanelDistanceMeters = 1.2f

/** Size of Carry's own panel. The Activity main panel is system-sized and cannot hold Carry. */
private val CarryPanelSizeMeters = FloatSize2d(2.4f, 1.35f)

/**
 * Physical size of each Field marker panel, in meters. The marker's text is laid out in density
 * units, so a fixed physical panel makes that text's apparent size depend on the layout's pixel
 * count. At the 3 m radius, 0.8 m × 0.43 m (about 15° × 8° of view) keeps the title and distance
 * legible while leaving the marker clear of the centre of view.
 */
private val FieldMarkerSizeMeters = FloatSize2d(0.8f, 0.43f)

/**
 * Unit horizontal direction the device faces, as (x, z). Straight up or down there is no
 * horizontal facing, so the fallback is -Z, the default forward.
 */
private fun horizontalForward(forward: Vector3): Pair<Float, Float> {
    val length = sqrt(forward.x * forward.x + forward.z * forward.z)
    return if (length < 1e-4f) 0f to -1f else (forward.x / length) to (forward.z / length)
}

/**
 * Elevation, in radians, of a place on the Earth's surface seen from sea level at a great-circle
 * distance, on a spherical Earth. Negative means below the horizon. The member's eye height is
 * negligible at this scale and is treated as zero.
 */
private fun earthElevationRadians(distanceMeters: Double): Double {
    val radius = Geo.EARTH_RADIUS_METERS
    val centralAngle = distanceMeters / radius
    return atan2(radius * cos(centralAngle) - radius, radius * sin(centralAngle))
}

/**
 * One Field marker. Its position is fixed in the world by its anchor. Its orientation is not: it
 * is re-aimed at the member every frame, so the marker reads as a sign on the horizon that the
 * member moves around, not as a panel attached to the view (Carry does that; Field does not).
 */
private class PlacedMarker(
    private val anchor: Anchor,
    private val panel: PanelEntity,
    private val anchorPosition: Vector3,
) {
    fun faceToward(devicePosition: Vector3) {
        val toX = devicePosition.x - anchorPosition.x
        val toY = devicePosition.y - anchorPosition.y
        val toZ = devicePosition.z - anchorPosition.z
        val yaw = Math.toDegrees(atan2(toX.toDouble(), toZ.toDouble())).toFloat()
        val pitch = Math.toDegrees(-atan2(toY.toDouble(), sqrt((toX * toX + toZ * toZ).toDouble()))).toFloat()
        panel.setPose(Pose(Vector3(0f, 0f, 0f), Quaternion.fromEulerAngles(pitch, yaw, 0f)))
    }

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
            val (forwardX, forwardZ) = horizontalForward(forward)

            val relativeRadians = Math.toRadians(bearingDegrees - headingDegrees)
            val cosA = cos(relativeRadians).toFloat()
            val sinA = sin(relativeRadians).toFloat()
            // Same rotation-sign convention as the old renderer's placeAnchor: real-world
            // bearing increases clockwise viewed from above, so a positive bearing offset must
            // rotate forward's -Z component toward +X, not -X -- a naively-ported left-handed
            // rotation sign put a "15 right" test marker far to the left instead, caught live
            // on-device in the classic-ARCore renderer this replaces.
            val directionX = forwardX * cosA - forwardZ * sinA
            val directionZ = forwardX * sinA + forwardZ * cosA

            // True geography sets the direction. The member's bearing gives the horizontal
            // heading; the Earth's curvature for the real great-circle distance gives the
            // elevation, so a distant place sits below the horizon as it would in the world.
            // Geographic distance stays data: the marker sits at a fixed presentation radius, and
            // the real distance still reaches markerContent via distanceMeters.
            val elevation = earthElevationRadians(distanceMeters)
            val radius = FieldPresentationRadiusMeters
            val horizontalScale = (cos(elevation) * radius).toFloat()
            val anchorPosition =
                Vector3(
                    devicePose.translation.x + directionX * horizontalScale,
                    devicePose.translation.y + (sin(elevation) * radius).toFloat(),
                    devicePose.translation.z + directionZ * horizontalScale,
                )
            val anchorPose = Pose(anchorPosition, Quaternion.fromEulerAngles(0f, 0f, 0f))

            // The panel faces the member: its front turns toward the device's position.
            val toMemberX = devicePose.translation.x - anchorPosition.x
            val toMemberY = devicePose.translation.y - anchorPosition.y
            val toMemberZ = devicePose.translation.z - anchorPosition.z
            val facing =
                Quaternion.fromEulerAngles(
                    pitch = Math.toDegrees(-atan2(toMemberY.toDouble(), sqrt((toMemberX * toMemberX + toMemberZ * toMemberZ).toDouble()))).toFloat(),
                    yaw = Math.toDegrees(atan2(toMemberX.toDouble(), toMemberZ.toDouble())).toFloat(),
                    roll = 0f,
                )

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
                            // This panel is hosted outside the Activity's theme, so it must apply
                            // ElonnTheme itself. Without it the marker falls back to the default
                            // light Material scheme: a white box with dark text.
                            setContent {
                                ElonnTheme {
                                    markerContent(obj, distanceMeters, Modifier.fillMaxSize())
                                }
                            }
                        }
                    val panel =
                        PanelEntity.create(
                            session,
                            composeView,
                            FieldMarkerSizeMeters,
                            "field-marker-${obj.id}",
                            Pose(Vector3(0f, 0f, 0f), facing),
                            anchorSpace,
                        )
                    return PlacedMarker(anchor, panel, anchorPosition)
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
