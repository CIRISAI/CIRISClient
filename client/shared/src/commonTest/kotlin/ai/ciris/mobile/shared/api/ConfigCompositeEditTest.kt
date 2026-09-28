package ai.ciris.mobile.shared.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CSD-023: a list or a dict value survives the text editor. The edit text is
 * the value's JSON, and the edit is written back as the same JSON type — never
 * as a string that happens to look like one (the node reads its values typed).
 */
class ConfigCompositeEditTest {

    private val list = Json.parseToJsonElement("""["a","b"]""") as JsonArray
    private val dict = Json.parseToJsonElement("""{"x":1,"y":true}""") as JsonObject

    @Test
    fun anUnchangedListRoundTripsAsAList() {
        val written = configValueFor(configEditText(list), list)
        assertEquals(list, written, "saving an unchanged list must not stringify it")
        assertTrue(written is JsonArray)
    }

    @Test
    fun anUnchangedObjectRoundTripsAsAnObject() {
        val written = configValueFor(configEditText(dict), dict)
        assertEquals(dict, written)
        assertTrue(written is JsonObject)
    }

    @Test
    fun anEditedListIsParsedAsAList() {
        assertEquals(Json.parseToJsonElement("""["a","b","c"]"""), configValueFor("""["a", "b", "c"]""", list))
    }

    @Test
    fun aCompositeEditThatIsNotItsTypeIsRefusedWithAReason() {
        val e = assertFailsWith<IllegalArgumentException> { configValueFor("a, b, c", list) }
        assertTrue("JSON" in (e.message ?: ""), "says how to edit it: ${e.message}")
        assertFailsWith<IllegalArgumentException>("a list is not an object") { configValueFor("""["x"]""", dict) }
    }

    @Test
    fun primitivesAreUnchanged() {
        assertEquals(JsonPrimitive(true), configValueFor("true", JsonPrimitive(false)))
        assertEquals(JsonPrimitive(7L), configValueFor("7", JsonPrimitive(3)))
        assertEquals(JsonPrimitive("seven"), configValueFor("seven", JsonPrimitive("six")))
        assertEquals("7", configEditText(JsonPrimitive(7)))
    }
}
