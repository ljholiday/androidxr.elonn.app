package com.elonn.androidxr.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Starting JVM unit test coverage for RuntimeModel.kt's pure-logic parsing (World Dataset
 * conformance remediation, Phase 4c) -- no test infrastructure existed in this repo at all
 * before this. Covers firstImageMediaResource() and the body-node traversal
 * (firstMatchingBodyNode/firstImageMediaNode) it drives, the one platform-wide shared rule
 * for "find the first eligible image media node in content.body" (object.md, Content body;
 * decision.thumbnail_is_a_media_node_not_a_canonical_field_20261024).
 */
class RuntimeModelTest {

    private fun objectWithBody(bodyJson: String): WorldObject = WorldObject(
        id = "object:test",
        type = "test.object",
        title = "",
        summary = "",
        content = JSONObject("""{"body": $bodyJson}"""),
    )

    private fun stateWithResources(vararg resources: WorldResource): RuntimeState =
        RuntimeState.Empty.copy(resourcesById = resources.associateBy { it.id })

    @Test
    fun resolvesADirectTopLevelImageMediaNode() {
        val obj = objectWithBody("""[{"node": "media", "kind": "image", "resource_or_object_ref": "resource:thumb:1"}]""")
        val state = stateWithResources(WorldResource(id = "resource:thumb:1", kind = "image", source = "https://img.example.com/a.jpg", label = ""))

        val resource = firstImageMediaResource(obj, state)

        assertEquals("https://img.example.com/a.jpg", resource?.source)
    }

    @Test
    fun skipsALeadingTextNodeAndFindsTheImageMediaNodeAfterIt() {
        val obj = objectWithBody(
            """[
                {"node": "paragraph", "text": "Some text before the image."},
                {"node": "media", "kind": "image", "resource_or_object_ref": "resource:thumb:1"}
            ]"""
        )
        val state = stateWithResources(WorldResource(id = "resource:thumb:1", kind = "image", source = "https://img.example.com/a.jpg", label = ""))

        assertEquals("https://img.example.com/a.jpg", firstImageMediaResource(obj, state)?.source)
    }

    @Test
    fun recursesIntoASectionNodeToFindAnImageMediaNode() {
        val obj = objectWithBody(
            """[{"node": "section", "kind": "gallery", "content": [
                {"node": "media", "kind": "image", "resource_or_object_ref": "resource:thumb:1"}
            ]}]"""
        )
        val state = stateWithResources(WorldResource(id = "resource:thumb:1", kind = "image", source = "https://img.example.com/section.jpg", label = ""))

        assertEquals("https://img.example.com/section.jpg", firstImageMediaResource(obj, state)?.source)
    }

    @Test
    fun recursesIntoAGroupNodeToFindAnImageMediaNode() {
        val obj = objectWithBody(
            """[{"node": "group", "content": [
                {"node": "media", "kind": "image", "resource_or_object_ref": "resource:thumb:1"}
            ]}]"""
        )
        val state = stateWithResources(WorldResource(id = "resource:thumb:1", kind = "image", source = "https://img.example.com/group.jpg", label = ""))

        assertEquals("https://img.example.com/group.jpg", firstImageMediaResource(obj, state)?.source)
    }

    @Test
    fun recursesIntoAListItemsOwnContentToFindAnImageMediaNode() {
        val obj = objectWithBody(
            """[{"node": "list", "ordered": false, "items": [
                {"content": [{"node": "paragraph", "text": "item one"}]},
                {"content": [{"node": "media", "kind": "image", "resource_or_object_ref": "resource:thumb:1"}]}
            ]}]"""
        )
        val state = stateWithResources(WorldResource(id = "resource:thumb:1", kind = "image", source = "https://img.example.com/list.jpg", label = ""))

        assertEquals("https://img.example.com/list.jpg", firstImageMediaResource(obj, state)?.source)
    }

    @Test
    fun ignoresANonImageMediaNodeLikeAnEmbed() {
        val obj = objectWithBody("""[{"node": "media", "kind": "embed", "resource_or_object_ref": "resource:embed:1"}]""")
        val state = stateWithResources(WorldResource(id = "resource:embed:1", kind = "embed", source = "https://embed.example.com/1", label = ""))

        assertNull(firstImageMediaResource(obj, state))
    }

    @Test
    fun compactBodyTextPrefersSummaryWhenPresent() {
        val obj = objectWithBody("""[{"node": "paragraph", "text": "Body text"}]""").copy(summary = "A real summary")

        assertEquals("A real summary", compactBodyText(obj))
    }

    @Test
    fun compactBodyTextFallsBackToFirstBodyTextWhenSummaryIsBlank() {
        // decision.summary_is_descriptive_not_a_content_excerpt_20261024: a conversation
        // message whose Service deliberately leaves summary blank since content.body already
        // IS its only real content -- a compact card's presentation choice, not a derived
        // summary field.
        val obj = objectWithBody("""[{"node": "paragraph", "text": "Hey, are we still on for lunch?"}]""")

        assertEquals("Hey, are we still on for lunch?", compactBodyText(obj))
    }

    @Test
    fun compactBodyTextReturnsBlankWhenNeitherSummaryNorBodyTextExist() {
        val obj = objectWithBody("""[{"node": "media", "kind": "image", "resource_or_object_ref": "resource:1"}]""")

        assertEquals("", compactBodyText(obj))
    }

    @Test
    fun returnsNullWhenContentBodyIsAbsent() {
        val obj = WorldObject(id = "object:test", type = "test.object", title = "", summary = "", content = JSONObject("{}"))

        assertNull(firstImageMediaResource(obj, RuntimeState.Empty))
    }

    @Test
    fun returnsNullWhenTheReferencedResourceIsNotInTheDataset() {
        val obj = objectWithBody("""[{"node": "media", "kind": "image", "resource_or_object_ref": "resource:missing:1"}]""")

        assertNull(firstImageMediaResource(obj, RuntimeState.Empty))
    }

    @Test
    fun actionLabelFallsBackToActionWhenWorldSendsAnExplicitEmptyString() {
        // optString(key, fallback) only falls back for an absent/null key -- an explicit ""
        // (present but empty) slipped through un-defaulted until this was changed to
        // .ifBlank{}, matching web.elonn.local's common.text() and the identical fix made to
        // unity.elonn.app's Text() after this exact gap let an Action's own .type leak onto a
        // button there.
        val dataset = JSONObject(
            """{
                "id": "dataset:world:1", "type": "world",
                "objects": [], "collections": [], "relationships": [], "resources": [], "placements": [], "errors": [],
                "actions": [
                    {"id": "action:1", "type": "operation_invocation", "target": "",
                     "content": {"label": ""}}
                ]
            }"""
        )

        val state = RuntimeInterpreter.apply(dataset)

        assertEquals("Action", state.actionsById["action:1"]?.label)
    }

    @Test
    fun resourceIsImageOnlyWhenKindIsImageAndSourceIsPresent() {
        val image = WorldResource(id = "r1", kind = "image", source = "https://img.example.com/a.jpg", label = "")
        val embed = WorldResource(id = "r2", kind = "embed", source = "https://embed.example.com/1", label = "")
        val blankSourceImage = WorldResource(id = "r3", kind = "image", source = "", label = "")

        assert(image.isImage)
        assert(!embed.isImage)
        assert(!blankSourceImage.isImage)
    }

    @Test
    fun resourceIsExternalReferenceOnlyWhenNeitherEmbeddableNorImage() {
        val link = WorldResource(id = "r1", kind = "website.link", source = "https://example.com", label = "")
        val embed = WorldResource(id = "r2", kind = "embed", source = "https://embed.example.com/1", label = "")
        val image = WorldResource(id = "r3", kind = "image", source = "https://img.example.com/a.jpg", label = "")

        assert(link.isExternalReference)
        assert(!embed.isExternalReference)
        assert(!image.isExternalReference)
    }
}
