package com.elonn.androidxr

import android.content.Context

/**
 * Which Field presentation this device uses. A headset with Android XR reports the spatial
 * feature and gets Field through SceneCore (ArCorePassthrough.kt). Anything else, such as an
 * ordinary phone, gets the classic ARCore camera passthrough (ClassicArField.kt).
 *
 * This is decided from the device's advertised capability, not by trying SceneCore and falling
 * back when it fails. On a phone, SceneCore has no backend and can throw during session creation,
 * so a try-then-fallback choice would depend on exceptions instead of on what the device is.
 */
fun usesSpatialFieldPresentation(context: Context): Boolean =
    context.packageManager.hasSystemFeature("android.software.xr.api.spatial")
