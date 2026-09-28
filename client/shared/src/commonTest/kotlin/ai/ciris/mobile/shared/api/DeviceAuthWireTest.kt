package ai.ciris.mobile.shared.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The node's Portal device grant, as CIRISServer `src/auth/device_auth.rs`
 * actually serves it: bare bodies, `{"error": …}` refusals. The bodies below are
 * transcribed from `ConnectNodeResponse` (`:100-108`),
 * `ConnectNodeStatusResponse` (`:231-247`) and `err` (`:94-96`).
 */
class DeviceAuthWireTest {

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun theNodesBareConnectBodyParses() {
        val r = DeviceAuthWire.parseConnect(
            obj("""{"verification_uri_complete":"https://portal.ciris.ai/device?c=AB12","device_code":"dc","user_code":"AB12","portal_url":"https://portal.ciris.ai","expires_in":600,"interval":7}"""),
            "portal.ciris.ai",
        )
        assertEquals("AB12", r.userCode)
        assertEquals("https://portal.ciris.ai", r.portalUrl)
        assertEquals(7, r.interval)
    }

    @Test
    fun theAgentEnvelopeStillParses() {
        val r = DeviceAuthWire.parseConnect(obj("""{"data":{"device_code":"dc","user_code":"X","verification_uri_complete":"u"}}"""), "https://portal.ciris.ai")
        assertEquals("X", r.userCode)
    }

    /**
     * A body with no device code / user code / verification URI is an older or
     * malformed node, not a grant: WAITING with a blank code and a poll with an
     * empty `device_code` is what it used to become.
     */
    @Test
    fun aConnectBodyMissingTheCodesIsRefusedByName() {
        val e = assertFailsWith<DeviceAuthRefused> {
            DeviceAuthWire.parseConnect(obj("""{"portal_url":"https://portal.ciris.ai","expires_in":600}"""), "portal.ciris.ai")
        }
        assertTrue("device_code" in (e.message ?: ""), "names what is missing: ${e.message}")
        assertFailsWith<DeviceAuthRefused>("blank is missing") {
            DeviceAuthWire.parseConnect(obj("""{"verification_uri_complete":"u","device_code":"","user_code":"AB12"}"""), "portal.ciris.ai")
        }
    }

    @Test
    fun aCompletedPollKeepsWhatThePortalApproved() {
        val r = DeviceAuthWire.parsePoll(
            obj("""{"status":"complete","template":"ally","adapters":["api","discord"],"org_id":"org-1","stewardship_tier":2,"package_download_url":"https://portal.ciris.ai/p.zip","package_template_id":"ally"}"""),
        )
        assertEquals("complete", r.status)
        assertEquals(listOf("api", "discord"), r.adapters, "approved_adapters was dropped as a TODO")
        assertEquals(2, r.stewardshipTier)
        assertEquals("https://portal.ciris.ai/p.zip", r.packageDownloadUrl)
    }

    @Test
    fun aPendingPollIsPending() {
        assertEquals("pending", DeviceAuthWire.parsePoll(obj("""{"status":"pending"}""")).status)
    }

    @Test
    fun theNodesRefusalReasonIsRead() {
        assertEquals(
            "Invalid portal URL: Untrusted host 'evil.example.com'. Only CIRIS Portal domains are allowed.",
            DeviceAuthWire.refusalReason(obj("""{"error":"Invalid portal URL: Untrusted host 'evil.example.com'. Only CIRIS Portal domains are allowed."}""")),
        )
        assertEquals("nope", DeviceAuthWire.refusalReason(obj("""{"detail":"nope"}""")))
    }
}
