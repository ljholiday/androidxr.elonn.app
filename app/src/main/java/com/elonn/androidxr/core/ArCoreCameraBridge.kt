package com.elonn.androidxr.core

import android.content.Context
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session

/**
 * Owns a plain, ordinary ARCore Session directly -- no Jetpack XR Session
 * wrapper, no Geospatial, no Google Cloud account or API key, nothing but
 * ARCore's own on-device motion tracking. This is the standard way every
 * ARCore app has worked since ARCore 1.0.
 *
 * GPS and compass (Geo.kt, FieldCamera.kt) are used exactly once, at
 * [createAnchor] time, to compute a Field object's position relative to
 * wherever the camera's own local tracking origin currently is. After that,
 * the Anchor's own tracked Pose is what moves the marker -- this class and
 * its caller never touch device pitch/yaw/roll or recompute a bearing again.
 *
 * Session.update() must be called from a thread with a current EGL context
 * (the classic ARCore camera-texture mode requires it) -- [update] is meant
 * to be called from the GLSurfaceView's own render thread, not a background
 * dispatcher. That off-GL-thread mismatch (via Jetpack XR's own Session
 * update loop) is what crashed live on-device with MissingGlContextException
 * before this was simplified back to owning the Session directly.
 */
class ArCoreCameraBridge private constructor(val session: Session) {

    fun resume() = session.resume()

    fun pause() = session.pause()

    fun setCameraTextureName(textureId: Int) = session.setCameraTextureName(textureId)

    /** Must be called from the GL thread; see class doc. */
    fun update(): Frame = session.update()

    /** A real, on-device ARCore Anchor -- no cloud call, tracked purely by local VIO from here on. */
    fun createAnchor(pose: Pose) = session.createAnchor(pose)

    companion object {
        /**
         * Creates and configures a plain ARCore Session. Returns null if
         * ARCore isn't installed/supported -- the caller decides how to
         * surface that (e.g. ArCoreApk.getInstance().requestInstall for the
         * "not installed" case), this is not the place to guess at recovery.
         */
        fun create(context: Context): ArCoreCameraBridge? {
            val availability = ArCoreApk.getInstance().checkAvailability(context)
            if (!availability.isSupported) return null

            val session = Session(context)
            val config =
                Config(session).apply {
                    // ARCore's actual default -- explicit here only so it's
                    // never silently changed by some other Config default
                    // shifting underneath this app later.
                    textureUpdateMode = Config.TextureUpdateMode.BIND_TO_TEXTURE_EXTERNAL_OES
                    // Never enabled: Geospatial requires a live Google Cloud
                    // API key/account. Local motion tracking and local
                    // Anchors need no such thing.
                    geospatialMode = Config.GeospatialMode.DISABLED
                }
            session.configure(config)
            return ArCoreCameraBridge(session)
        }
    }
}
