package com.elonn.androidxr.core

import org.json.JSONObject

/**
 * Ported against dev.elonn.local's canonical docs (object.md, dataset.md,
 * finding.md, placement.md, action.md, context.md, resource.md) and verified
 * against web.elonn.local's actual runtime-kit (dataset-parser.js,
 * state-indexer.js, web-renderer.js) -- not inferred from xreal.elonn.app's
 * Unity code alone, which this session found to have real gaps of its own.
 * Find (search) and full document/region interpretation (html.md's
 * `document` Object with a region tree) are not modeled yet.
 */
data class WorldObject(
    val id: String,
    val type: String,
    val title: String,
    val summary: String,
    val content: JSONObject,
    val resourceIds: List<String> = emptyList(),
    val actionIds: MutableList<String> = mutableListOf(),
) {
    /** Same fallback web.elonn's meta line uses: summary if present, else the type. */
    val meta: String get() = summary.ifBlank { type }

    /**
     * content.location (verified against maps.elonn.local's
     * MapsCallHandler::fieldObjects: {latitude, longitude}) -- the real-world
     * position Field actually places this Object at. Null for anything World
     * didn't attach coordinates to.
     */
    val location: Pair<Double, Double>?
        get() {
            val loc = content.optJSONObject("location") ?: return null
            if (!loc.has("latitude") || !loc.has("longitude")) return null
            return loc.optDouble("latitude") to loc.optDouble("longitude")
        }
}

/**
 * resource.md: a Resource is never a loose URL a Runtime reads to decide
 * what to do. Only a `kind == "embed"` Resource is an Elonn-constructed,
 * provider-table-vetted executable target (oembed.md) safe to actually load;
 * anything else following an external `source` is presented as a reference
 * only (web.elonn.local's externalRef -- domain shown, no navigation at
 * all), never fetched or embedded by this Runtime.
 */
data class WorldResource(
    val id: String,
    val kind: String,
    val source: String,
    val label: String,
) {
    val isEmbeddable: Boolean get() = kind == "embed" && source.isNotBlank()
    val isExternalReference: Boolean get() = !isEmbeddable && source.startsWith("http")
}

data class RuntimeAction(
    val id: String,
    val type: String,
    val targetId: String,
    val label: String,
    val operationInvocation: JSONObject?,
    val enabled: Boolean,
    val reason: String,
) {
    /** action.md: an entry with no operation_invocation is not a canonical Action. */
    val isDispatchable: Boolean get() = enabled && operationInvocation != null

    val argumentKeys: List<String>
        get() = operationInvocation?.optJSONObject("arguments")?.keys()?.asSequence()?.toList().orEmpty()

    /** Dispatchable with no argument schema -- a plain one-tap button. */
    val isSimpleAction: Boolean get() = isDispatchable && argumentKeys.isEmpty()

    /** Dispatchable but needs input first -- rendered as a real form, not a bare button. */
    val needsForm: Boolean get() = isDispatchable && argumentKeys.isNotEmpty()
}

data class WorldCollection(
    val id: String,
    val type: String,
    val title: String,
    val summary: String,
    val itemIds: List<String>,
)

private data class WorldPlacement(
    val type: String,
    val objectId: String,
    val collectionId: String,
)

data class ZoneState(
    val objectIds: List<String> = emptyList(),
    val collectionIds: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = objectIds.isEmpty() && collectionIds.isEmpty()
}

data class FindingEntry(val kind: String, val id: String)

/**
 * context.objects[id] (dataset.md, context.md): per-Object navigation state
 * World owns for an Object opened on Carry. `title` takes precedence over
 * the Object's own canonical title (web.elonn.local's openedObjects()).
 * `history` isn't carried across in full here (only its presence matters,
 * for the back control); depth/subject/region are read but not yet acted on.
 */
data class NavigationEntry(
    val title: String,
    val depth: Int,
    val hasHistory: Boolean,
    val subject: String,
    val region: String,
)

/** dataset.md: World's own single resolved severity+message for this Dataset. */
data class DatasetStatus(val severity: String, val message: String)

data class RuntimeState(
    val datasetId: String,
    val objectsById: Map<String, WorldObject>,
    val collectionsById: Map<String, WorldCollection>,
    val actionsById: Map<String, RuntimeAction>,
    val resourcesById: Map<String, WorldResource>,
    val navigationById: Map<String, NavigationEntry>,
    val field: ZoneState,
    val carry: ZoneState,
    val findings: List<FindingEntry>,
    val selectedObjectId: String,
    val status: DatasetStatus,
) {
    /** context.objects[id].title, falling back to the Object's own title (web.elonn parity). */
    fun carryTitle(obj: WorldObject): String =
        navigationById[obj.id]?.title?.takeIf { it.isNotBlank() } ?: obj.title.ifBlank { obj.id }

    companion object {
        val Empty = RuntimeState(
            datasetId = "",
            objectsById = emptyMap(),
            collectionsById = emptyMap(),
            actionsById = emptyMap(),
            resourcesById = emptyMap(),
            navigationById = emptyMap(),
            field = ZoneState(),
            carry = ZoneState(),
            findings = emptyList(),
            selectedObjectId = "",
            status = DatasetStatus("ok", ""),
        )
    }
}

/**
 * Parses a raw canonical World Dataset (POST /world/call response) into a
 * RuntimeState.
 */
object RuntimeInterpreter {

    fun apply(dataset: JSONObject): RuntimeState {
        val objectsById = readObjects(dataset.optJSONArray("objects"))
        val collectionsById = readCollections(dataset.optJSONArray("collections"))
        val actionsById = readActions(dataset.optJSONArray("actions"))
        val resourcesById = readResources(dataset.optJSONArray("resources"))
        val placements = readPlacements(dataset.optJSONArray("placements"))

        attachActionsToObjects(objectsById, actionsById)

        val field = resolveZone("field", placements, objectsById, collectionsById)
        val carry = resolveZone("carry", placements, objectsById, collectionsById)
        val findings = resolveFindings(dataset.optJSONArray("findings"), objectsById, collectionsById)

        val context = dataset.optJSONObject("context")
        val selectedObjectId = context?.optJSONObject("focus")?.optString("object_id").orEmpty()
            .takeIf { objectsById.containsKey(it) } ?: ""
        val navigationById = readNavigation(context?.optJSONObject("objects"))
        val status = readStatus(context?.optJSONObject("status"))

        return RuntimeState(
            datasetId = dataset.optString("id"),
            objectsById = objectsById,
            collectionsById = collectionsById,
            actionsById = actionsById,
            resourcesById = resourcesById,
            navigationById = navigationById,
            field = field,
            carry = carry,
            findings = findings,
            selectedObjectId = selectedObjectId,
            status = status,
        )
    }

    private fun readObjects(items: org.json.JSONArray?): Map<String, WorldObject> {
        val result = LinkedHashMap<String, WorldObject>()
        for (payload in objects(items)) {
            val id = payload.optString("id")
            if (id.isBlank()) continue
            val content = payload.optJSONObject("content") ?: JSONObject()
            // An Object's Resources are named directly (object.resources, or, on this
            // platform's Datasets, content.resources) -- not discovered via any
            // attachment sweep the way Actions are (dataset-parser.js's idsFrom).
            val resourceIds = strings(payload.optJSONArray("resources") ?: content.optJSONArray("resources"))
            result[id] = WorldObject(
                id = id,
                type = payload.optString("type"),
                title = payload.optString("title"),
                summary = payload.optString("summary"),
                content = content,
                resourceIds = resourceIds,
            )
        }
        return result
    }

    private fun readCollections(items: org.json.JSONArray?): Map<String, WorldCollection> {
        val result = LinkedHashMap<String, WorldCollection>()
        for (payload in objects(items)) {
            val id = payload.optString("id")
            if (id.isBlank()) continue
            // A Collection's items list is top-level, not under content (see
            // world.elonn's DatasetValidator) -- reading content.items only
            // silently drops every Social list/search/feed/roster collection.
            val content = payload.optJSONObject("content")
            val itemsArray = payload.optJSONArray("items") ?: content?.optJSONArray("items")
            result[id] = WorldCollection(
                id = id,
                type = payload.optString("type"),
                title = payload.optString("title"),
                summary = payload.optString("summary"),
                itemIds = strings(itemsArray),
            )
        }
        return result
    }

    private fun readActions(items: org.json.JSONArray?): Map<String, RuntimeAction> {
        val result = LinkedHashMap<String, RuntimeAction>()
        for (payload in objects(items)) {
            val id = payload.optString("id")
            if (id.isBlank()) continue
            val content = payload.optJSONObject("content") ?: JSONObject()
            val availability = payload.optJSONObject("availability")
            result[id] = RuntimeAction(
                id = id,
                type = payload.optString("type"),
                targetId = payload.optString("target"),
                label = content.optString("label", "Action"),
                operationInvocation = content.optJSONObject("operation_invocation"),
                enabled = availability == null || availability.optString("state") == "enabled",
                reason = availability?.optString("reason").orEmpty(),
            )
        }
        return result
    }

    // resource.md: a Resource is never a loose URL field -- `source` is read
    // from content.href/url/source (dataset-parser.js's own precedence),
    // `kind` classifies it, and only kind=="embed" is ever executable.
    private fun readResources(items: org.json.JSONArray?): Map<String, WorldResource> {
        val result = LinkedHashMap<String, WorldResource>()
        for (payload in objects(items)) {
            val id = payload.optString("id")
            if (id.isBlank()) continue
            val content = payload.optJSONObject("content") ?: JSONObject()
            val source = content.optString("href").ifBlank { content.optString("url") }.ifBlank { content.optString("source") }
            result[id] = WorldResource(
                id = id,
                kind = content.optString("kind", payload.optString("type", "resource")),
                source = source,
                label = content.optString("label").ifBlank { content.optString("name") }.ifBlank { id },
            )
        }
        return result
    }

    private fun readPlacements(items: org.json.JSONArray?): List<WorldPlacement> {
        val result = mutableListOf<WorldPlacement>()
        for (payload in objects(items)) {
            val type = payload.optString("type")
            if (type != "carry" && type != "field") continue
            val content = payload.optJSONObject("content") ?: JSONObject()
            result.add(
                WorldPlacement(
                    type = type,
                    objectId = content.optString("object"),
                    collectionId = content.optString("collection"),
                )
            )
        }
        return result
    }

    // context.objects (dataset.md, context.md): title/depth/history/subject/region
    // World owns per Object opened on Carry.
    private fun readNavigation(contextObjects: JSONObject?): Map<String, NavigationEntry> {
        val result = LinkedHashMap<String, NavigationEntry>()
        if (contextObjects == null) return result
        val ids = contextObjects.keys()
        while (ids.hasNext()) {
            val objectId = ids.next()
            val entry = contextObjects.optJSONObject(objectId) ?: continue
            val history = entry.optJSONArray("history")
            result[objectId] = NavigationEntry(
                title = entry.optString("title"),
                depth = entry.optInt("depth", 0).coerceAtLeast(0),
                hasHistory = history != null && history.length() > 0,
                subject = entry.optString("subject"),
                region = entry.optString("region"),
            )
        }
        return result
    }

    private fun readStatus(status: JSONObject?): DatasetStatus {
        if (status == null) return DatasetStatus("ok", "")
        val severity = status.optString("severity", "ok").let {
            if (it == "ok" || it == "notice" || it == "error") it else "error"
        }
        return DatasetStatus(severity, status.optString("message"))
    }

    private fun attachActionsToObjects(objectsById: Map<String, WorldObject>, actionsById: Map<String, RuntimeAction>) {
        for (action in actionsById.values) {
            if (action.targetId.isNotBlank()) {
                objectsById[action.targetId]?.actionIds?.add(action.id)
            }
        }
    }

    private fun resolveZone(
        placementType: String,
        placements: List<WorldPlacement>,
        objectsById: Map<String, WorldObject>,
        collectionsById: Map<String, WorldCollection>,
    ): ZoneState {
        val objectIds = LinkedHashSet<String>()
        val collectionIds = LinkedHashSet<String>()
        for (placement in placements) {
            if (placement.type != placementType) continue
            if (placement.collectionId.isNotBlank() && collectionsById.containsKey(placement.collectionId)) {
                collectionIds.add(placement.collectionId)
            }
            if (placement.objectId.isNotBlank() && objectsById.containsKey(placement.objectId)) {
                objectIds.add(placement.objectId)
            }
        }
        return ZoneState(objectIds.toList(), collectionIds.toList())
    }

    private fun resolveFindings(
        entries: org.json.JSONArray?,
        objectsById: Map<String, WorldObject>,
        collectionsById: Map<String, WorldCollection>,
    ): List<FindingEntry> {
        val result = mutableListOf<FindingEntry>()
        val seenObjects = HashSet<String>()
        val seenCollections = HashSet<String>()
        for (entry in objects(entries)) {
            val content = entry.optJSONObject("content") ?: continue
            val collectionId = content.optString("collection")
            val objectId = content.optString("object")
            if (collectionId.isNotBlank() && collectionsById.containsKey(collectionId) && seenCollections.add(collectionId)) {
                result.add(FindingEntry("collection", collectionId))
            } else if (objectId.isNotBlank() && objectsById.containsKey(objectId) && seenObjects.add(objectId)) {
                result.add(FindingEntry("object", objectId))
            }
        }
        return result
    }

    private fun objects(array: org.json.JSONArray?): List<JSONObject> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { array.opt(it) as? JSONObject }
    }

    private fun strings(array: org.json.JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { array.opt(it)?.toString()?.takeIf { s -> s.isNotBlank() } }
    }
}
