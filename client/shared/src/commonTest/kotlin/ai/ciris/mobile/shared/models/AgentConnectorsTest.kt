package ai.ciris.mobile.shared.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CSD-020 §7 — connectors on the Adapters card. Bodies are the shapes
 * CIRISAgent main's `routes/connectors.py` returns.
 */
class AgentConnectorsTest {

    private fun testDto(success: Boolean, message: String) =
        ConnectorTestDto("sql_postgres_1a2b3c4d", success, message, 1.7, "2026-09-25T11:00:00Z")

    /**
     * With no tool bus the agent answers "success" and never touches the
     * database (`connectors.py:383-384`); every REST test is simulated
     * (`:399-401`). Neither is a pass.
     */
    @Test
    fun aTestThatDidNotRunIsNeverAPass() {
        assertIs<ConnectorTestOutcome.NotRun>(
            connectorTestOutcome(testDto(true, "SQL connection test successful (tool bus unavailable, skipped)")),
        )
        assertIs<ConnectorTestOutcome.NotRun>(
            connectorTestOutcome(testDto(true, "REST API connection test successful (simulated)")),
        )
    }

    @Test
    fun aRealPassAndARealFailureReadAsWhatTheyAre() {
        assertIs<ConnectorTestOutcome.Passed>(connectorTestOutcome(testDto(true, "SQL connection test successful")))
        val failed = connectorTestOutcome(testDto(false, "Connection test error: timeout"))
        assertIs<ConnectorTestOutcome.Failed>(failed)
        assertEquals("Connection test error: timeout", failed.message)
    }

    /** A failed test is HTTP 200 with `success: false` inside `data` (`connectors.py:470-477`). */
    @Test
    fun aFailedTestArrivesAsAReadNotAnError() {
        val read = agentReadOf(
            200,
            """{"success":false,"message":"Connection test failed","data":{"connector_id":"sql_postgres_1a2b3c4d",
              "success":false,"message":"Connection test failed","latency_ms":12.5,"tested_at":"2026-09-25T11:00:00Z"}}""",
            ConnectorTestDto.serializer(),
        )
        assertIs<AgentRead.Ok<ConnectorTestDto>>(read)
        assertIs<ConnectorTestOutcome.Failed>(connectorTestOutcome(read.value))
    }

    /** No standing the card can draw says "connected" (CIRISAgent#1211: nothing was recorded). */
    @Test
    fun noStandingClaimsAConnection() {
        for (status in listOf("registered", "healthy", "unhealthy", "disabled", "something-new")) {
            val key = connectorStandingKey(ConnectorDto("id", "sql", "Orders", status = status))
            assertFalse("connected" in key, "$status → $key")
        }
        assertEquals(
            "adapters.connector_standing_registered",
            connectorStandingKey(ConnectorDto("id", "sql", "Orders", status = "registered")),
        )
        assertEquals(
            "adapters.connector_standing_disabled",
            connectorStandingKey(ConnectorDto("id", "sql", "Orders", status = "disabled")),
        )
    }

    /**
     * The list decodes without a secret in it, and even a body that DID carry
     * one could not surface it: [ConnectorDto] has nowhere to put it.
     */
    @Test
    fun theSecretHasNowhereToLand() {
        val read = agentReadOf(
            200,
            """{"success":true,"data":{"total":1,"connectors":[{"connector_id":"sql_postgres_1a2b3c4d",
              "connector_type":"sql","connector_name":"Orders","status":"registered",
              "registered_at":"2026-09-25T10:00:00Z","last_tested":null,"last_test_result":null,"total_requests":0,
              "config":{"password":"hunter2","username":"svc"}}]}}""",
            ConnectorListDto.serializer(),
        )
        assertIs<AgentRead.Ok<ConnectorListDto>>(read)
        val list = connectorsListOf(read)
        assertIs<ConnectorsList.Ready>(list)
        assertFalse("hunter2" in list.connectors.toString())
    }

    @Test
    fun aForbiddenListIsNotAnEmptyOne() {
        assertEquals(
            ConnectorsList.AdminOnly,
            connectorsListOf(agentReadOf(403, """{"detail":"Only administrators can list connectors"}""", ConnectorListDto.serializer())),
        )
    }

    /** What a SQL connector hands the agent includes deletion — the card must say so. */
    @Test
    fun theSqlGrantNamesDeletion() {
        val tools = connectorGrantTools("sql")
        assertTrue("sql_delete_user" in tools)
        assertTrue("sql_anonymize_user" in tools)
        assertEquals(7, tools.size)
        assertTrue(connectorGrantTools("hl7").isEmpty(), "no route registers hl7; the card says 'not known'")
    }
}
