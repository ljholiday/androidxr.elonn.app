package com.elonn.androidxr.core

import org.json.JSONObject

/**
 * World-owned display content for the query panel (context.content.find on every World Dataset
 * response) -- title, placeholder, and button labels, read off the Dataset and never as literals
 * in the renderer (mirrors web.elonn.local's find-content reads in web-renderer.js and
 * unity.elonn.app's RuntimeModels.cs FindContent). Defaults mirror world-content.json's own
 * defaults, used only when an older/degraded response omits context.content.find.
 *
 * scopeEnum/scopeDefault are parsed but deliberately not surfaced as a selector yet
 * (next_step.find_scope_selector_is_decorative_in_web_and_unity_20261024 on agents.elonn.com --
 * web and Unity both already have scope-selector UI, but neither actually sends the chosen scope
 * to World; porting that UI here would just be a third copy of the same decorative control).
 */
data class FindContent(
    val title: String,
    val placeholder: String,
    val searchLabel: String,
    val clearLabel: String,
    val scopeEnum: List<String>,
    val scopeDefault: String,
) {
    companion object {
        val Empty = FindContent(
            title = "Find",
            placeholder = "Find coffee, food, parks...",
            searchLabel = "Search",
            clearLabel = "Clear",
            scopeEnum = listOf("nearby", "everywhere"),
            scopeDefault = "everywhere",
        )
    }
}

/**
 * World-owned title-bar labels for an Object opened on Carry (context.content.object;
 * dev.elonn canonical/layout.md, "Object presentation") -- the Back step and the
 * collapse/expand/close controls, read off the Dataset and never as literals in the renderer
 * (mirrors unity.elonn.app's RuntimeModels.cs ObjectContent). Defaults mirror world-content.json.
 */
data class ObjectContent(
    val backLabel: String,
    val collapseLabel: String,
    val expandLabel: String,
    val closeLabel: String,
) {
    companion object {
        val Empty = ObjectContent(
            backLabel = "Back",
            collapseLabel = "Collapse",
            expandLabel = "Expand",
            closeLabel = "Close",
        )
    }
}

/** context.content.find (parallel to DesignTokens.kt's readTheme()). Absent/malformed falls back to FindContent.Empty's defaults field by field, not a crash. */
internal fun readFindContent(find: JSONObject?): FindContent {
    if (find == null) return FindContent.Empty
    val scope = find.optJSONObject("scope")
    val scopeEnumJson = scope?.optJSONArray("enum")
    val scopeEnum = if (scopeEnumJson != null && scopeEnumJson.length() > 0) {
        (0 until scopeEnumJson.length()).mapNotNull { scopeEnumJson.optString(it).takeIf { s -> s.isNotBlank() } }
    } else {
        FindContent.Empty.scopeEnum
    }
    return FindContent(
        title = find.optString("title").ifBlank { FindContent.Empty.title },
        placeholder = find.optString("placeholder").ifBlank { FindContent.Empty.placeholder },
        searchLabel = find.optString("search_label").ifBlank { FindContent.Empty.searchLabel },
        clearLabel = find.optString("clear_label").ifBlank { FindContent.Empty.clearLabel },
        scopeEnum = scopeEnum,
        scopeDefault = scope?.optString("default")?.ifBlank { FindContent.Empty.scopeDefault } ?: FindContent.Empty.scopeDefault,
    )
}

/** context.content.object (parallel to DesignTokens.kt's readTheme()). Absent/malformed falls back to ObjectContent.Empty's defaults field by field, not a crash. */
internal fun readObjectContent(obj: JSONObject?): ObjectContent {
    if (obj == null) return ObjectContent.Empty
    return ObjectContent(
        backLabel = obj.optString("back_label").ifBlank { ObjectContent.Empty.backLabel },
        collapseLabel = obj.optString("collapse_label").ifBlank { ObjectContent.Empty.collapseLabel },
        expandLabel = obj.optString("expand_label").ifBlank { ObjectContent.Empty.expandLabel },
        closeLabel = obj.optString("close_label").ifBlank { ObjectContent.Empty.closeLabel },
    )
}
