package com.elonn.androidxr.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Standard great-circle formulas, ported exactly from xreal.elonn.app's
 * Geo.cs. Field Objects carry raw lat/lon (content.location, from Maps --
 * verified against maps.elonn.local's MapsCallHandler::fieldObjects, which
 * writes content.location = {latitude, longitude}) but no bearing -- the
 * canonical contract has no such field, so this is computed client-side from
 * the member's current GPS position.
 */
object Geo {
    private const val EARTH_RADIUS_METERS = 6371000.0

    /** Initial bearing in degrees (0-360, 0 = true north), from point 1 to point 2. */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = toRadians(lat1)
        val phi2 = toRadians(lat2)
        val deltaLambda = toRadians(lon2 - lon1)

        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        val theta = atan2(y, x)

        return (toDegrees(theta) + 360.0) % 360.0
    }

    /** Haversine great-circle distance in meters. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = toRadians(lat1)
        val phi2 = toRadians(lat2)
        val deltaPhi = toRadians(lat2 - lat1)
        val deltaLambda = toRadians(lon2 - lon1)

        val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
            cos(phi1) * cos(phi2) * sin(deltaLambda / 2) * sin(deltaLambda / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return EARTH_RADIUS_METERS * c
    }

    /**
     * Ported from xreal.elonn.app's RuntimeInterpreter.FieldProjectionX: the
     * phone-camera-passthrough projection (continuous compass heading, not a
     * one-time AR-session calibration like the XREAL-headset-specific
     * ArFieldRenderer.cs uses) -- the right one for a plain camera view.
     * Returns null when the bearing falls outside the camera's horizontal FOV.
     */
    fun fieldProjectionX(bearing: Double, deviceHeading: Double, hFov: Double, viewportWidth: Double): Double? {
        val relative = ((bearing - deviceHeading + 540.0) % 360.0) - 180.0
        if (kotlin.math.abs(relative) > hFov / 2.0) return null
        return (relative / hFov + 0.5) * viewportWidth
    }

    private fun toRadians(degrees: Double) = degrees * PI / 180.0
    private fun toDegrees(radians: Double) = radians * 180.0 / PI
}
