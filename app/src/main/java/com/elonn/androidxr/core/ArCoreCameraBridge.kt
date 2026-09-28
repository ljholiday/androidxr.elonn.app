package com.elonn.androidxr.core

import android.content.Context
import androidx.xr.runtime.Config
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.SessionCreateSuccess

/**
 * Creates and configures the Jetpack XR Session Field's perception (Anchor/Trackable poses,
 * device pose) and spatial rendering (SceneCore Entities attached to those poses, see
 * ArCorePassthrough.kt) run on.
 *
 * Unlike classic ARCore, this Session owns its own render loop internally -- there is no
 * GLSurfaceView, no manual per-frame Session.update() call, and no GL-thread requirement
 * anywhere in this app. That matters here specifically: an earlier attempt at using Jetpack
 * XR's Session wrapper was reverted because its own internal update loop ran off the GL thread
 * and crashed on-device with MissingGlContextException. This rewrite has no app-owned render
 * loop for that update loop to conflict with in the first place -- SceneCore renders everything.
 *
 * DeviceTrackingMode.SPATIAL must be configured explicitly: a fresh Session defaults to
 * DISABLED, which throws IllegalStateException from ArDevice.getInstance() the moment anything
 * reads the device's current pose -- confirmed live via a real crash on the emulator, not
 * assumed from docs.
 *
 * No GeospatialMode/Google Cloud account or API key anywhere: this app only ever creates local
 * Anchors from a Pose it computes itself (GPS bearing/distance, see Geo.kt), never a Geospatial
 * anchor -- matching decision.native_android_xr_candidate_runtime_20260924's explicit rejection
 * of an ongoing Google Cloud dependency.
 */
suspend fun createXrSession(context: Context): Session? {
    val session =
        when (val result = Session.create(context)) {
            is SessionCreateSuccess -> result.session
            else -> return null
        }
    val configured = session.configure(Config(deviceTracking = DeviceTrackingMode.SPATIAL))
    return if (configured is SessionConfigureSuccess) session else null
}
