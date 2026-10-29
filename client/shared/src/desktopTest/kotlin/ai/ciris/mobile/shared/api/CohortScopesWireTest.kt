package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.viewmodels.LEGACY_COHORT_SCOPES
import ai.ciris.mobile.shared.viewmodels.cohortChoices
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * **The claim's cohort picker offers what the node accepts** (CIRISServer#666,
 * 0.5.242): `GET /v1/setup/status` → `data.cohort_scopes`, widening order. An
 * older node declares nothing and accepts the three.
 */
class CohortScopesWireTest {

    private fun node(body: String): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/setup/status") { ex ->
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private val seven = listOf("self", "family", "community", "affiliations", "species", "biosphere", "federation")

    @Test
    fun aNewNodesListIsReadInItsOwnOrder() {
        val s = node("""{"data":{"setup_required":false,"cohort_scopes":${seven.joinToString(",", "[", "]") { "\"$it\"" }}},"metadata":{}}""")
        try {
            val got = runBlocking { CIRISApiClient("http://127.0.0.1:1", null).getNodeCohortScopes("http://127.0.0.1:${s.address.port}") }
            assertEquals(seven, got)
            assertEquals(seven, cohortChoices(got))
        } finally {
            s.stop(0)
        }
    }

    @Test
    fun anOlderNodeDeclaresNothingAndTheThreeAreOffered() {
        val s = node("""{"data":{"setup_required":false},"metadata":{}}""")
        try {
            val got = runBlocking { CIRISApiClient("http://127.0.0.1:1", null).getNodeCohortScopes("http://127.0.0.1:${s.address.port}") }
            assertNull(got)
            assertEquals(LEGACY_COHORT_SCOPES, cohortChoices(got))
        } finally {
            s.stop(0)
        }
    }

    @Test
    fun anUnreachableNodeIsTheOldThreeNotAnError() {
        val got = runBlocking { CIRISApiClient("http://127.0.0.1:1", null).getNodeCohortScopes("http://127.0.0.1:1") }
        assertNull(got)
        assertEquals(LEGACY_COHORT_SCOPES, cohortChoices(got))
    }

    @Test
    fun selfAlwaysLeadsAndAListWithoutSelfIsNotTrusted() {
        assertEquals(listOf("self", "family", "federation"), cohortChoices(listOf("family", "self", "federation")))
        assertEquals(LEGACY_COHORT_SCOPES, cohortChoices(listOf("family", "community")))
        assertEquals(LEGACY_COHORT_SCOPES, cohortChoices(emptyList()))
    }
}
