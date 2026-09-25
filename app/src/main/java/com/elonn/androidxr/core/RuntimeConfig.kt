package com.elonn.androidxr.core

/**
 * Same base URLs xreal.elonn.app's runtime-config.json points at -- production,
 * not a *.local dev host, since a phone/headset is a separate physical device
 * and this is how the real product is actually used and tested end to end.
 */
object RuntimeConfig {
    const val API_BASE_URL = "https://api.elonn.com"
    const val WORLD_BASE_URL = "https://world.elonn.com"
    const val RUNTIME_ID = "androidxr"
    const val VERSION = "0.1.0"
}
