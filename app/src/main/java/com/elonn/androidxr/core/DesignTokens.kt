package com.elonn.androidxr.core

import org.json.JSONObject

/**
 * One resolved design token (dev.elonn canonical theme model;
 * decision.member_theming_design_token_system_corrected_20261023 on agents.elonn.com). `type` is
 * one of `color` (a `#rrggbb` String), `relative` (a Number multiplier relative to this Runtime's
 * own base unit), `weight` (a Number font-weight), `keyword` (a String like `"full"`, meaningful
 * only to this Runtime's own interpretation), or `font_family` (a literal String).
 */
data class ThemeToken(val value: Any?, val type: String)

/** The member's fully resolved effective theme -- context.theme on every World Dataset response. */
data class ResolvedTheme(
    val theme: String,
    val version: Int,
    val scale: String,
    val tokens: Map<String, ThemeToken>,
) {
    fun color(key: String): String? = tokens[key]?.takeIf { it.type == "color" }?.value as? String
    fun relative(key: String): Double? = (tokens[key]?.takeIf { it.type == "relative" }?.value as? Number)?.toDouble()
    fun weight(key: String): Int? = (tokens[key]?.takeIf { it.type == "weight" }?.value as? Number)?.toInt()
    fun keyword(key: String): String? = tokens[key]?.takeIf { it.type == "keyword" }?.value as? String
    fun fontFamily(key: String): String? = tokens[key]?.takeIf { it.type == "font_family" }?.value as? String

    companion object {
        val Empty = ResolvedTheme(theme = "", version = 0, scale = "md", tokens = emptyMap())
    }
}

/** context.theme (parallel to RuntimeModel.kt's readStatus()). Absent/malformed is RuntimeState.Empty's theme, not a crash. */
internal fun readTheme(theme: JSONObject?): ResolvedTheme {
    if (theme == null) return ResolvedTheme.Empty

    val tokensJson = theme.optJSONObject("tokens")
    val tokens = LinkedHashMap<String, ThemeToken>()
    if (tokensJson != null) {
        val keys = tokensJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val tokenJson = tokensJson.optJSONObject(key) ?: continue
            tokens[key] = ThemeToken(value = tokenJson.opt("value"), type = tokenJson.optString("type"))
        }
    }

    return ResolvedTheme(
        theme = theme.optString("theme"),
        version = theme.optInt("version"),
        scale = theme.optString("scale", "md"),
        tokens = tokens,
    )
}
