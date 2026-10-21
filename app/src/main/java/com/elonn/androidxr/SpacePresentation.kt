package com.elonn.androidxr

import android.content.Context

/**
 * Which Space presentation this device uses. A headset with Android XR reports the spatial
 * feature and gets Space through SceneCore (ArCorePassthrough.kt). Anything else, such as an
 * ordinary phone, gets the classic ARCore camera passthrough (ClassicArSpace.kt).
 *
 * This is decided from the device's advertised capability, not by trying SceneCore and falling
 * back when it fails. On a phone, SceneCore has no backend and can throw during session creation,
 * so a try-then-fallback choice would depend on exceptions instead of on what the device is.
 */
fun usesSpatialSpacePresentation(context: Context): Boolean =
    context.packageManager.hasSystemFeature("android.software.xr.api.spatial")
