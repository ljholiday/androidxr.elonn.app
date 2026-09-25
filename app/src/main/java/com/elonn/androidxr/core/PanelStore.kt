package com.elonn.androidxr.core

import android.content.Context

private const val PREFS_FILE = "elonn_panels"
private const val KEY_NEXT_Z = "next_z"

/**
 * Per-window geometry, Android's equivalent of xreal.elonn.app's
 * CarryPanelStore (PlayerPrefs) / web.elonn.local's localStorage carry-panel
 * geometry. World owns which Objects are open on Carry; this only remembers
 * where the member left each window -- position, size, collapsed, stacking
 * order -- keyed by panel id: a real World object id, or "entry" for the one
 * non-closable window Entry and the Results pane share (dev.elonn.local's
 * layout.md: collapsing it is the Results pane's show/hide -- "only Entry
 * remains visible"). Forgotten when its Object closes; "entry" is never
 * forgotten, since it never closes.
 */
class PanelStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun load(panelId: String): PanelGeometry? {
        if (!prefs.contains(xKey(panelId))) return null
        return PanelGeometry(
            x = prefs.getFloat(xKey(panelId), 0f),
            y = prefs.getFloat(yKey(panelId), 0f),
            width = prefs.getFloat(wKey(panelId), 0f),
            height = prefs.getFloat(hKey(panelId), 0f),
            collapsed = prefs.getBoolean(collapsedKey(panelId), false),
            z = prefs.getFloat(zKey(panelId), 1f),
        )
    }

    fun save(panelId: String, geometry: PanelGeometry) {
        prefs.edit()
            .putFloat(xKey(panelId), geometry.x)
            .putFloat(yKey(panelId), geometry.y)
            .putFloat(wKey(panelId), geometry.width)
            .putFloat(hKey(panelId), geometry.height)
            .putBoolean(collapsedKey(panelId), geometry.collapsed)
            .putFloat(zKey(panelId), geometry.z)
            .apply()
    }

    fun forget(panelId: String) {
        prefs.edit()
            .remove(xKey(panelId)).remove(yKey(panelId))
            .remove(wKey(panelId)).remove(hKey(panelId))
            .remove(collapsedKey(panelId)).remove(zKey(panelId))
            .apply()
    }

    /**
     * Web.elonn.local's floatingPanel() persists a `z` per panel the same
     * way ("drag / resize / collapse / z-order"); this is Android's stacking
     * order equivalent -- one running counter shared by every window, so
     * "raise" always means strictly on top of every other window, and the
     * order survives a process restart the same way position/size do.
     */
    fun raise(panelId: String): Float {
        val next = prefs.getFloat(KEY_NEXT_Z, 1f) + 1f
        prefs.edit().putFloat(KEY_NEXT_Z, next).apply()
        return next
    }

    private fun xKey(id: String) = "x_$id"
    private fun yKey(id: String) = "y_$id"
    private fun wKey(id: String) = "w_$id"
    private fun hKey(id: String) = "h_$id"
    private fun collapsedKey(id: String) = "collapsed_$id"
    private fun zKey(id: String) = "z_$id"
}

data class PanelGeometry(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val collapsed: Boolean,
    val z: Float = 1f,
)
