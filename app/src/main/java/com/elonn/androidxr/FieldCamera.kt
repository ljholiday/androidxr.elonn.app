package com.elonn.androidxr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Only CAMERA/ACCESS_FINE_LOCATION gate Field's UI. SCENE_UNDERSTANDING_COARSE (required by
 * androidx.xr.arcore's Anchor.create) is requested alongside these two (see ArCoreField's
 * permissionLauncher) but deliberately NOT required here -- confirmed live on a real Galaxy S24:
 * that permission never appears in the device's runtime-permission grant list at all, meaning
 * this ordinary phone's OS has no grant UI for an Android XR-specific permission, so requiring it
 * would permanently block Field on any non-XR device. Anchor.create failing for a real lack of
 * scene-understanding access is already handled gracefully by placeMarker's retry-then-give-up
 * logic, which is the right place for that failure to surface, not a permanent UI block here.
 */
fun hasFieldPermissions(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * The member's live GPS position, from the platform LocationManager -- no
 * extra Play Services dependency needed for the one-provider case this app
 * needs. Used exactly once per Field object, at real-anchor-creation time
 * (see ArCorePassthrough.kt's ArFieldRenderer.syncAnchors) -- ARCore's own
 * tracked Anchor pose is what moves markers on every frame after that, never
 * this location stream directly.
 */
@Composable
fun rememberDeviceLocation(): State<Location?> {
    val context = LocalContext.current
    val locationState = remember { mutableStateOf<Location?>(null) }

    DisposableEffect(Unit) {
        if (!hasFieldPermissions(context)) {
            return@DisposableEffect onDispose {}
        }
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = LocationListener { location -> locationState.value = location }
        val provider = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            LocationManager.GPS_PROVIDER
        } else {
            LocationManager.NETWORK_PROVIDER
        }
        try {
            locationManager.getLastKnownLocation(provider)?.let { locationState.value = it }
            locationManager.requestLocationUpdates(provider, 2000L, 3f, listener)
        } catch (e: SecurityException) {
            // Permission was revoked between the check above and this call -- leave
            // locationState null; the caller renders "no fix yet" either way.
        }
        onDispose { locationManager.removeUpdates(listener) }
    }

    return locationState
}

/**
 * True compass heading (0 = north), from the rotation-vector sensor. Used
 * exactly once per Field object, at real-anchor-creation time, to establish
 * which direction in ARCore's local tracking space corresponds to true
 * north (see ArFieldRenderer.placeAnchor's doc) -- never read again after
 * that for an already-placed anchor. Device pitch and roll are never needed
 * at all: ARCore's own tracked camera pose supplies the real per-frame
 * orientation, which is exactly the "no device pitch/yaw/roll compensation"
 * this Runtime was directed to avoid doing itself.
 *
 * The default rotation matrix assumes the device is lying flat on a table
 * (typical compass-app orientation); remapCoordinateSystem(AXIS_X, AXIS_Z)
 * is the standard remap for reading heading from a phone held upright as a
 * camera viewfinder instead.
 */
@Composable
fun rememberDeviceHeading(): State<Double> {
    val context = LocalContext.current
    val headingState = remember { mutableStateOf(0.0) }

    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            private val rotationMatrix = FloatArray(9)
            private val remappedMatrix = FloatArray(9)
            private val orientation = FloatArray(3)

            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remappedMatrix)
                SensorManager.getOrientation(remappedMatrix, orientation)
                val headingDegrees = (Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0

                // Raw rotation-vector heading is noisy indoors (magnetometer
                // interference from steel framing/electronics); smoothed so a
                // newly-placed anchor's calibration doesn't pick up a spurious
                // jump. Circular shortest-path smoothing (wraps at 360).
                val previous = headingState.value
                val delta = ((headingDegrees - previous + 540.0) % 360.0) - 180.0
                headingState.value = (previous + 0.2 * delta + 360.0) % 360.0
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        if (rotationSensor != null) {
            sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose { sensorManager.unregisterListener(listener) }
    }

    return headingState
}
