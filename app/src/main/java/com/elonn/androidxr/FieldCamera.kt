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
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

fun hasCameraAndLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * The member's live GPS position, from the platform LocationManager -- no
 * extra Play Services dependency needed for the one-provider case this app
 * needs. Field markers have nothing to place relative to until this reports
 * a real fix.
 */
@Composable
fun rememberDeviceLocation(): State<Location?> {
    val context = LocalContext.current
    val locationState = remember { mutableStateOf<Location?>(null) }

    DisposableEffect(Unit) {
        if (!hasCameraAndLocationPermission(context)) {
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
 * True compass heading in degrees (0 = north), from the rotation-vector
 * sensor -- the phone-camera equivalent of the compass calibration
 * xreal.elonn.app's ArFieldRenderer does once per AR session, except this
 * reads continuously (RuntimeInterpreter.FieldProjectionX, which Geo.kt
 * ports, takes a live heading rather than a one-time calibration).
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
            private val orientation = FloatArray(3)

            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val azimuthRadians = orientation[0]
                val degrees = (Math.toDegrees(azimuthRadians.toDouble()) + 360.0) % 360.0
                // Raw rotation-vector heading is noisy indoors (magnetometer
                // interference from steel framing/electronics) -- a real AR view
                // needs this smoothed or markers flicker in and out as the
                // compass jumps, even while the phone is held still. Circular
                // exponential smoothing (shortest-path delta, wrapped 0-360).
                val previous = headingState.value
                val delta = ((degrees - previous + 540.0) % 360.0) - 180.0
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

/** A live camera passthrough preview -- Field's actual background, not a screen behind a list. */
@Composable
fun CameraPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                // CameraX defaults to a SurfaceView-backed implementation, which
                // composites as its own layer and can render outside/over the
                // bounds Compose actually laid it out in once other content
                // (markers, the top bar, Carry) overlaps it. COMPATIBLE mode
                // uses a TextureView instead, drawn in the normal view
                // hierarchy, which is what CameraX's own docs recommend
                // whenever the preview is layered with other UI.
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                } catch (e: Exception) {
                    // No back camera available on this device/emulator config -- the
                    // preview stays blank; markers still compute and render on top.
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

