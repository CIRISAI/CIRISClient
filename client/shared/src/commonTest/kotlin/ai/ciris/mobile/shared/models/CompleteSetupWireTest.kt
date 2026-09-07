package ai.ciris.mobile.shared.models

import ai.ciris.mobile.shared.api.toWireJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What `POST /v1/setup/complete` actually sends (CIRISClient#41).
 *
 * `RunWithoutAiDeliveryTest` proves the choice survives to the MODEL. It
 * passed on the release where the agent received `false`, because the loss was
 * one step later: the model was re-typed into a generated SDK model that had no
 * such field. These tests read the bytes, so a field the model declares and the
 * wire lacks fails here rather than in the agent's log.
 */
class CompleteSetupWireTest {

    /** Every property set to a non-default, non-null value. */
    private fun everything() = CompleteSetupRequest(
        llm_provider = "openrouter",
        llm_api_key = "sk-x",
        llm_base_url = "https://openrouter.ai/api/v1",
        llm_model = "qwen/qwen3",
        backup_llm_api_key = "sk-y",
        backup_llm_base_url = "https://b",
        backup_llm_model = "m2",
        template_id = "default",
        enabled_adapters = listOf("api", "ciris_accord_metrics"),
        adapter_config = mapOf("k" to "v"),
        agent_port = 8081,
        system_admin_password = "p1",
        admin_username = "qaadmin",
        admin_password = "p2",
        oauth_provider = "google",
        oauth_external_id = "gid",
        oauth_email = "e@x",
        preferred_language = "am",
        location_country = "Ethiopia",
        location_region = "Addis Ababa",
        location_city = "Addis Ababa",
        location_latitude = 9.03,
        location_longitude = 38.74,
        timezone = "Africa/Addis_Ababa",
        share_location_in_traces = true,
        trace_analyze = false,
        run_without_ai = true,
        node_url = "https://node",
        identity_template = "tmpl",
        stewardship_tier = 3,
        approved_adapters = listOf("api"),
        org_id = "org",
        signing_key_provisioned = true,
        provisioned_signing_key_b64 = "AAAA",
        signing_key_id = "kid",
    )

    private fun wire(r: CompleteSetupRequest) = Json.parseToJsonElement(r.toWireJson()).jsonObject

    @Test
    fun every_declared_field_reaches_the_wire() {
        // THE TEST THAT WOULD HAVE CAUGHT #41. The old path passed 21 of 36
        // fields to the wire and nothing counted. This counts.
        val declared = CompleteSetupRequest.serializer().descriptor.let { d ->
            (0 until d.elementsCount).map { d.getElementName(it) }.toSet()
        }
        val sent = wire(everything()).keys
        val dropped = declared - sent
        assertTrue(dropped.isEmpty(), "declared by the model, missing from the wire: $dropped")
        assertEquals(declared, sent)
    }

    @Test
    fun choosing_without_ai_is_sent_as_true() {
        assertTrue(wire(everything())["run_without_ai"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun not_choosing_it_is_sent_as_an_explicit_false() {
        // An honest negative, not an absence the server fills in. If this ever
        // becomes absent, `encodeDefaults` was turned off and the admin/owner
        // default collision is back too.
        val r = everything().copy(run_without_ai = false, admin_username = "admin")
        val w = wire(r)
        assertFalse(w["run_without_ai"]!!.jsonPrimitive.boolean)
        assertEquals(JsonPrimitive("admin"), w["admin_username"], "the value that equals a default must still be SENT")
    }

    @Test
    fun the_other_choices_the_old_path_dropped_are_sent() {
        val w = wire(everything())
        assertFalse(w["trace_analyze"]!!.jsonPrimitive.boolean)
        assertTrue(w["share_location_in_traces"]!!.jsonPrimitive.boolean)
        assertEquals(JsonPrimitive("am"), w["preferred_language"])
        assertEquals(JsonPrimitive("Africa/Addis_Ababa"), w["timezone"])
        assertEquals(JsonPrimitive(3), w["stewardship_tier"])
    }

    @Test
    fun unset_optionals_are_omitted_so_server_defaults_apply() {
        val r = CompleteSetupRequest(
            llm_provider = "openai", llm_api_key = "", template_id = "default",
            enabled_adapters = listOf("api"), system_admin_password = "p", admin_username = "u",
        )
        val w = wire(r)
        assertFalse("llm_model" in w)
        assertFalse("signing_key_provisioned" in w)
        assertFalse("location_latitude" in w)
        // and the names are the agent's, verbatim
        assertTrue("run_without_ai" in w && "llm_provider" in w && "enabled_adapters" in w)
    }
}
