package com.elonn.androidxr.core

import org.json.JSONObject

/**
 * Ported against dev.elonn.local's canonical docs (object.md, dataset.md,
 * finding.md, placement.md, action.md, context.md, resource.md) and verified
 * against web.elonn.local's actual runtime-kit (dataset-parser.js,
 * state-indexer.js, web-renderer.js) -- not inferred from xreal.elonn.app's
 * Unity code alone, which this session found to have real gaps of its own.
 */
data class WorldObject(
    val id: String,
    val type: String,
    val title: String,
    val summary: String,
    val content: JSONObject,
    val resourceIds: List<String> = emptyList(),
    val actionIds: MutableList<String> = mutableListOf(),
    /**
     * The real-world position this Object is placed at, read from its own `space` Placement's
     * content.position (dev.elonn canonical/placement.md, Space position) -- never from the
     * Object's own content. An Object's own descriptive geographic data, if it ever carries any,
     * and its Placement's position are different facts and are never collapsed into one field
     * (placement.md: "the two are different facts and are never collapsed into one field").
     * RuntimeInterpreter.apply() attaches this after placements are parsed; null for an Object
     * with no `space` Placement, or one with no position.
     */
    val location: Pair<Double, Double>? = null,
) {
    /**
     * The Finding card's meta line -- summary only. web.elonn.local never
     * falls back to showing an Object's raw `type` here (web-renderer.js only
     * appends a meta/summary node `if (object.summary !== '')`, otherwise
     * omits the line entirely); this file previously claimed that fallback
     * matched web, which was wrong and went unnoticed until a `world.thread`
     * Object (summary always blank) surfaced it as a literal "world.thread"
     * line under a search's title.
     */
    val meta: String get() = summary
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
    // resource.md, Preview presentation: a Runtime presents a Resource whose own declared
    // kind identifies it as an image as a visual preview, from the Resource's own kind alone
    // -- never inferred from the owning Service or the field name that referenced it.
    val isImage: Boolean get() = kind == "image" && source.isNotBlank()
    val isExternalReference: Boolean get() = !isEmbeddable && !isImage && source.startsWith("http")
}

/**
 * The first eligible image media node in an Object's content.body, in document order
 * (dev.elonn canonical/object.md, Content body; decision.thumbnail_is_a_media_node_not_a_
 * canonical_field_20261024), resolved against the Dataset's flat resources list. Used both
 * for a compact card's own preview and for BodyMedia's opened-panel rendering -- never a
 * Service-specific content.thumbnail field, and never inferred by scanning an Object's
 * Resources for one that merely looks like an image.
 */
fun firstImageMediaResource(obj: WorldObject, state: RuntimeState): WorldResource? {
    val node = firstImageMediaNode(obj.content.optJSONArray("body")) ?: return null
    val resourceId = node.optString("resource_or_object_ref")
    if (resourceId.isBlank()) return null
    return state.resourcesById[resourceId]
}

private fun firstImageMediaNode(nodes: org.json.JSONArray?): JSONObject? =
    firstMatchingBodyNode(nodes) { node -> node.optString("node") == "media" && node.optString("kind") == "image" }

/**
 * The one traversal every "find the first X in content.body" need shares -- depth-first,
 * document order, recursing through group/section/list. A compact or opened presentation
 * never hand-rolls its own partial walk of the content-node vocabulary (object.md, Content
 * body); it asks this for the first node matching what it needs, so every such need stays
 * consistent with what a full body render would show first, and with each other.
 */
private fun firstMatchingBodyNode(nodes: org.json.JSONArray?, predicate: (JSONObject) -> Boolean): JSONObject? {
    if (nodes == null) return null
    for (index in 0 until nodes.length()) {
        val node = nodes.optJSONObject(index) ?: continue
        if (predicate(node)) return node
        val kind = node.optString("node")
        if (kind == "section" || kind == "group") {
            val found = firstMatchingBodyNode(node.optJSONArray("content"), predicate)
            if (found != null) return found
        }
        if (kind == "list") {
            val items = node.optJSONArray("items") ?: continue
            for (itemIndex in 0 until items.length()) {
                val item = items.optJSONObject(itemIndex) ?: continue
                val found = firstMatchingBodyNode(item.optJSONArray("content"), predicate)
                if (found != null) return found
            }
        }
    }
    return null
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

/** placement.md: "A Placement positions one Object, never a Collection" -- objectId only. */
private data class WorldPlacement(
    val type: String,
    val objectId: String,
    val position: Pair<Double, Double>?,
)

data class ZoneState(
    val objectIds: List<String> = emptyList(),
    /** Object id -> (latitude, longitude), for every Object in this zone with a real position. */
    val positions: Map<String, Pair<Double, Double>> = emptyMap(),
) {
    val isEmpty: Boolean get() = objectIds.isEmpty()
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
    val threadId: String,
    val objectsById: Map<String, WorldObject>,
    val collectionsById: Map<String, WorldCollection>,
    val actionsById: Map<String, RuntimeAction>,
    val resourcesById: Map<String, WorldResource>,
    val navigationById: Map<String, NavigationEntry>,
    val space: ZoneState,
    val carry: ZoneState,
    val findings: List<FindingEntry>,
    val selectedObjectId: String,
    val status: DatasetStatus,
    val theme: ResolvedTheme,
    val findContent: FindContent,
    val objectContent: ObjectContent,
) {
    /** context.objects[id].title, falling back to the Object's own title (web.elonn parity). */
    fun carryTitle(obj: WorldObject): String =
        navigationById[obj.id]?.title?.takeIf { it.isNotBlank() } ?: obj.title.ifBlank { obj.id }

    companion object {
        val Empty = RuntimeState(
            datasetId = "",
            threadId = "",
            objectsById = emptyMap(),
            collectionsById = emptyMap(),
            actionsById = emptyMap(),
            resourcesById = emptyMap(),
            navigationById = emptyMap(),
            space = ZoneState(),
            carry = ZoneState(),
            findings = emptyList(),
            selectedObjectId = "",
            status = DatasetStatus("ok", ""),
            theme = ResolvedTheme.Empty,
            findContent = FindContent.Empty,
            objectContent = ObjectContent.Empty,
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

        val space = resolveZone("space", placements, objectsById)
        val carry = resolveZone("carry", placements, objectsById)
        // A space Placement's own content.position (never the Object's own content) is where a
        // marker's real-world coordinates live (placement.md, Space position) -- attach it onto
        // the matching WorldObject here, once, so every existing consumer of
        // [WorldObject.location] (ClassicArSpace.kt, ArCorePassthrough.kt) is unaffected.
        val positionedObjectsById = if (space.positions.isEmpty()) {
            objectsById
        } else {
            objectsById.mapValues { (id, obj) -> space.positions[id]?.let { obj.copy(location = it) } ?: obj }
        }
        val findings = resolveFindings(dataset.optJSONArray("findings"), positionedObjectsById, collectionsById)

        val context = dataset.optJSONObject("context")
        val selectedObjectId = context?.optJSONObject("focus")?.optString("object_id").orEmpty()
            .takeIf { positionedObjectsById.containsKey(it) } ?: ""
        val navigationById = readNavigation(context?.optJSONObject("objects"))
        val status = readStatus(context?.optJSONObject("status"))
        val theme = readTheme(context?.optJSONObject("theme"))
        val content = context?.optJSONObject("content")
        val findContent = readFindContent(content?.optJSONObject("find"))
        val objectContent = readObjectContent(content?.optJSONObject("object"))
        // context.thread_id (dev.elonn canonical/thread.md, world.elonn's WorldService::withThreadId):
        // always present on a response -- the id to continue, or "" when world.clear ended the
        // Thread. Never fabricated client-side; just echoed back verbatim on the next Call.
        val threadId = context?.optString("thread_id").orEmpty()

        return RuntimeState(
            datasetId = dataset.optString("id"),
            threadId = threadId,
            objectsById = positionedObjectsById,
            collectionsById = collectionsById,
            actionsById = actionsById,
            resourcesById = resourcesById,
            navigationById = navigationById,
            space = space,
            carry = carry,
            findings = findings,
            selectedObjectId = selectedObjectId,
            status = status,
            theme = theme,
            findContent = findContent,
            objectContent = objectContent,
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
                // optString(key, fallback) only falls back for an absent/null key -- World
                // sending content.label present but "" would slip through un-defaulted
                // (org.json's optString(name, fallback) only checks JSONObject.NULL/absent, not
                // an explicit empty string). .ifBlank{} catches both, matching web.elonn.local's
                // common.text() and unity.elonn.app's Text() (fixed the same way after this
                // exact gap let an Action's own .Type leak onto a button there).
                label = content.optString("label").ifBlank { "Action" },
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
                kind = content.optString("kind").ifBlank { payload.optString("type") }.ifBlank { "resource" },
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
            if (type != "carry" && type != "space") continue
            val content = payload.optJSONObject("content") ?: JSONObject()
            result.add(
                WorldPlacement(
                    type = type,
                    objectId = content.optString("object"),
                    position = readPosition(content.optJSONObject("position")),
                )
            )
        }
        return result
    }

    /**
     * A Placement's content.position (dev.elonn canonical/placement.md, Space position): a
     * closed, extensible vocabulary on `type` -- only `geographic` is defined, requiring both
     * `latitude` and `longitude`. Anything else (wrong type, either coordinate missing) is not a
     * position at all; this Runtime does not validate range/finiteness itself -- World already
     * rejects an invalid position before this is ever sent.
     */
    private fun readPosition(position: JSONObject?): Pair<Double, Double>? {
        if (position == null || position.optString("type") != "geographic") return null
        if (!position.has("latitude") || !position.has("longitude")) return null
        return position.optDouble("latitude") to position.optDouble("longitude")
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
        val severity = status.optString("severity").ifBlank { "ok" }.let {
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
    ): ZoneState {
        val objectIds = LinkedHashSet<String>()
        val positions = LinkedHashMap<String, Pair<Double, Double>>()
        for (placement in placements) {
            if (placement.type != placementType) continue
            if (placement.objectId.isNotBlank() && objectsById.containsKey(placement.objectId)) {
                objectIds.add(placement.objectId)
                if (placement.position != null) {
                    positions[placement.objectId] = placement.position
                }
            }
        }
        return ZoneState(objectIds.toList(), positions)
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
