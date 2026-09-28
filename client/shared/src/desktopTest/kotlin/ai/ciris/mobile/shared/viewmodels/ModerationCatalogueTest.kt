package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.models.CatalogueSource
import ai.ciris.mobile.shared.ui.screens.ReadFailure
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **The Moderation and Duty-conferral cards read what the node offers, and say
 * so when they cannot** (CSD-065, CSD-090; CIRISClient#108, #109; CIRISServer#676).
 *
 * Driven against a real socket, as HonestStatesTest is: the defects lived in
 * how a served 404 / 500 / missing field travelled through the client into
 * the view model, and a fake client would agree with whatever its author
 * assumed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModerationCatalogueTest {

    private val hits = CopyOnWriteArrayList<String>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        CIRISApiClient.setLocalNodeUrl(CIRISApiClient.DEFAULT_LOCAL_NODE_URL)
    }

    private fun serving(
        routes: Map<String, Pair<Int, String>> = emptyMap(),
        fallback: Pair<Int, String> = 500 to """{"detail":"boom"}""",
    ): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            hits += "${exchange.requestMethod} ${exchange.requestURI.path}"
            val (status, body) = routes[exchange.requestURI.path] ?: fallback
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    /** These are NODE routes, so the node URL is pointed at the server too. */
    private fun withNode(server: HttpServer, block: suspend (CIRISApiClient) -> Unit) {
        val url = "http://127.0.0.1:${server.address.port}"
        CIRISApiClient.setLocalNodeUrl(url)
        try {
            runBlocking { block(CIRISApiClient(url)) }
        } finally {
            server.stop(0)
        }
    }

    private suspend fun awaitThat(what: String, condition: () -> Boolean) {
        try {
            withTimeout(15_000) { while (!condition()) delay(20) }
        } catch (e: Exception) {
            fail("timed out waiting for: $what")
        }
    }

    private val catalogue = """{
      "selection_cardinality": "set_valued_key_predicates",
      "commit_fields": ["selection", "selection_hash", "delegation_id", "reason"],
      "operations": [
        {"op":"preview","route":"/v1/admin/preview","tier":null,"scope":null,"quorum":0,"reverses":null,"reaches_substrate":false},
        {"op":"annotate","route":"/v1/admin/annotate","tier":0,"scope":"slash","quorum":1,"reverses":null,"reaches_substrate":false},
        {"op":"descend","route":"/v1/admin/descend","tier":3,"scope":"slash","quorum":3,"reverses":null,"reaches_substrate":true},
        {"op":"expunge","route":"/v1/admin/expunge","tier":5,"scope":"slash","quorum":1,"reverses":null,"reaches_substrate":true}
      ]}"""

    private fun selfStanding(delegations: String?, error: String? = null) = """{
      "source_locale":"en","tier":"S","node_key_id":"node-1",
      "owner_delegations": ${delegations ?: "null"},
      "owner_delegations_error": ${error?.let { "\"$it\"" } ?: "null"},
      "standings":{}}"""

    private fun delegation(id: String, scope: String) =
        """{"delegation_id":"$id","issuer_key_id":"owner","subject_key_id":"node-1","scope":"$scope","owner_binding":true,"cohort_scope":"federation","asserted_at":"2026-09-27T00:00:00Z"}"""

    // ── #109: the ladder from GET /v1/operations ──

    @Test
    fun the_ladder_renders_the_served_scope_and_a_served_op_it_never_saw() = withNode(
        serving(mapOf("/v1/operations" to (200 to catalogue), "/v1/admin/self" to (200 to selfStanding("[]")))),
    ) { client ->
        val vm = AdminLadderViewModel(client)
        vm.load()
        awaitThat("catalogue read") { vm.state.value.catalogueSource == CatalogueSource.Node }
        val s = vm.state.value
        assertEquals(listOf("annotate", "descend", "expunge"), s.rungs.map { it.op }, "graded rows only, node's order")
        assertEquals("slash", s.selectedOp.scope, "annotate's scope is the SERVED one, not the compiled `review`")
        assertEquals(3, s.rungs.first { it.op == "descend" }.quorum, "the quorum floor is the row's, not a constant")
        assertNull(s.rungs.first { it.op == "expunge" }.local)
    }

    @Test
    fun a_node_without_the_catalogue_keeps_the_compiled_ladder_and_says_so() = withNode(
        serving(mapOf("/v1/operations" to (404 to "{}"), "/v1/admin/self" to (200 to selfStanding("[]")))),
    ) { client ->
        val vm = AdminLadderViewModel(client)
        vm.load()
        awaitThat("catalogue fallback") { vm.state.value.catalogueSource !is CatalogueSource.NotLoaded }
        assertIs<CatalogueSource.NotOnThisNode>(vm.state.value.catalogueSource)
        assertEquals(10, vm.state.value.rungs.size, "the fallback is still a ladder")
    }

    @Test
    fun a_failed_catalogue_read_is_not_an_absent_route() = withNode(
        serving(mapOf("/v1/admin/self" to (200 to selfStanding("[]")))),
    ) { client ->
        val vm = AdminLadderViewModel(client)
        vm.load()
        awaitThat("catalogue failure") { vm.state.value.catalogueSource !is CatalogueSource.NotLoaded }
        assertIs<CatalogueSource.Unreadable>(vm.state.value.catalogueSource)
    }

    // ── CIRISServer#676: the node's own delegation ids ──

    @Test
    fun a_delegation_carrying_the_rungs_scope_is_prefilled_and_one_that_does_not_is_not() = withNode(
        serving(
            mapOf(
                "/v1/operations" to (404 to "{}"),
                "/v1/admin/self" to (200 to selfStanding("[${delegation("del-serve", "infra:serve")}, ${delegation("del-review", "review")}]")),
            ),
        ),
    ) { client ->
        val vm = AdminLadderViewModel(client)
        vm.load()
        awaitThat("delegations read") { vm.state.value.nodeDelegations is NodeDelegations.Read }
        // Annotate needs `review`: exactly one row carries it, so it is prefilled.
        assertEquals("del-review", vm.state.value.delegationId)
        assertEquals(listOf("del-review"), vm.state.value.usableDelegations.map { it.delegationId })
        // Quarantine needs `slash`: nothing carries it, so the prefilled id is
        // DROPPED rather than carried into a rung the node would refuse it on.
        vm.selectOp(vm.state.value.rungs.first { it.op == "quarantine" })
        assertEquals("", vm.state.value.delegationId)
        assertTrue(vm.state.value.usableDelegations.isEmpty())
    }

    @Test
    fun a_typed_delegation_id_is_never_overwritten() = withNode(
        serving(
            mapOf(
                "/v1/operations" to (404 to "{}"),
                "/v1/admin/self" to (200 to selfStanding("[${delegation("del-review", "review")}]")),
            ),
        ),
    ) { client ->
        val vm = AdminLadderViewModel(client)
        vm.setDelegationId("mine")
        vm.load()
        awaitThat("delegations read") { vm.state.value.nodeDelegations is NodeDelegations.Read }
        assertEquals("mine", vm.state.value.delegationId)
    }

    @Test
    fun a_node_before_0_5_218_returns_no_delegations_and_that_is_its_own_state() = withNode(
        serving(mapOf("/v1/operations" to (404 to "{}"), "/v1/admin/self" to (200 to selfStanding(null)))),
    ) { client ->
        val vm = AdminLadderViewModel(client)
        vm.load()
        awaitThat("delegations state") { vm.state.value.nodeDelegations !is NodeDelegations.NotLoaded }
        assertIs<NodeDelegations.NotReturned>(vm.state.value.nodeDelegations)
        assertEquals("", vm.state.value.delegationId)
    }

    @Test
    fun a_refused_standing_read_is_unreadable_not_empty() = withNode(
        serving(mapOf("/v1/operations" to (404 to "{}"), "/v1/admin/self" to (403 to """{"refused":true,"refusal":"session_absent"}"""))),
    ) { client ->
        val vm = AdminLadderViewModel(client)
        vm.load()
        awaitThat("delegations state") { vm.state.value.nodeDelegations !is NodeDelegations.NotLoaded }
        assertIs<NodeDelegations.Unreadable>(vm.state.value.nodeDelegations)
    }

    // ── CSD-065 §2: "we could not ask" is not "no moderator" ──

    @Test
    fun a_failed_named_moderator_read_is_a_failure_not_a_blank_verdict() = withNode(serving()) { client ->
        val vm = SafetyViewModel(client)
        vm.setCommunityKeyId("wa-comm-1")
        vm.loadNamedModerator()
        awaitThat("named-moderator failure") { vm.state.value.namedModeratorFailure != null }
        val s = vm.state.value
        assertIs<ReadFailure.Failed>(s.namedModeratorFailure)
        assertNull(s.namedModeratorVerdict)
        assertNull(s.error, "the failure has its own field, not the shared error line")
    }

    @Test
    fun a_node_without_the_named_moderator_route_says_so() = withNode(serving(fallback = 404 to "{}")) { client ->
        val vm = SafetyViewModel(client)
        vm.setCommunityKeyId("wa-comm-1")
        vm.loadNamedModerator()
        awaitThat("named-moderator failure") { vm.state.value.namedModeratorFailure != null }
        assertIs<ReadFailure.NotOnThisNode>(vm.state.value.namedModeratorFailure)
    }

    @Test
    fun a_new_community_drops_the_last_verdict() = withNode(
        serving(mapOf("/v1/safety/named-moderator/wa-comm-1" to (200 to """{"community_key_id":"wa-comm-1","existence":{"verdict":"operate","moderator_present":true},"fails_secure":true}"""))),
    ) { client ->
        val vm = SafetyViewModel(client)
        vm.setCommunityKeyId("wa-comm-1")
        vm.loadNamedModerator()
        awaitThat("verdict") { vm.state.value.namedModeratorVerdict != null }
        assertEquals("operate", vm.state.value.namedModeratorVerdict?.verdict)
        vm.setCommunityKeyId("wa-comm-2")
        assertNull(vm.state.value.namedModeratorVerdict, "a verdict about another community is not this one's")
    }

    // ── #108: the duty menu from GET /v1/vocabulary ──

    private val vocabulary = """{"delegation_scope":{"all":["infra:serve","moderate"],"infra":["infra:serve"],"agency":["agency:reason"],
      "moderation":["consent_revocation","moderate","takedown","review","slash","license","grant"]},"cohort_scope":{"all":["self"]}}"""

    @Test
    fun the_duty_menu_offers_every_served_duty_and_the_accord_cannot_tick_the_walled_ones() = withNode(
        serving(mapOf("/v1/vocabulary" to (200 to vocabulary), "/v1/operations" to (200 to catalogue))),
    ) { client ->
        val vm = DutyConferralViewModel(client)
        vm.loadCatalogues()
        assertEquals(CatalogueSource.Node, vm.dutySource.value)
        assertEquals(DutyConferralViewModel.ALL_DUTIES + listOf("license", "grant"), vm.dutyMenu.value)
        // CC 4.2.1: accord authority cannot reach the consent or licensure planes.
        vm.toggleDuty("consent_revocation")
        vm.toggleDuty("license")
        vm.toggleDuty("grant")
        assertEquals(setOf("moderate"), vm.duties.value, "walled duties are shown, never ticked")
        vm.toggleDuty("slash")
        assertEquals(setOf("moderate", "slash"), vm.duties.value)
        assertEquals(CatalogueSource.Node, vm.ladderSource.value)
        assertEquals(listOf("annotate", "descend", "expunge"), vm.rungs.value.map { it.op })
    }

    @Test
    fun a_node_without_the_vocabulary_keeps_the_compiled_menu_and_says_so() = withNode(
        serving(fallback = 404 to "{}"),
    ) { client ->
        val vm = DutyConferralViewModel(client)
        vm.loadCatalogues()
        assertIs<CatalogueSource.NotOnThisNode>(vm.dutySource.value)
        assertEquals(DutyConferralViewModel.ALL_DUTIES, vm.dutyMenu.value)
        assertIs<CatalogueSource.NotOnThisNode>(vm.ladderSource.value)
        assertEquals(10, vm.rungs.value.size)
    }

    @Test
    fun a_failed_vocabulary_read_is_not_an_empty_menu() = withNode(serving()) { client ->
        val vm = DutyConferralViewModel(client)
        vm.loadCatalogues()
        assertIs<CatalogueSource.Unreadable>(vm.dutySource.value)
        assertEquals(DutyConferralViewModel.ALL_DUTIES, vm.dutyMenu.value)
    }

    @Test
    fun no_accord_family_is_empty_not_error() = withNode(
        serving(mapOf("/v1/accord/family" to (404 to "{}")), fallback = 404 to "{}"),
    ) { client ->
        val vm = DutyConferralViewModel(client)
        vm.load()
        awaitThat("family read") { vm.sourceAbsent.value || vm.sourceError.value != null }
        assertTrue(vm.sourceAbsent.value)
        assertNull(vm.sourceError.value, "the node ANSWERED; nothing is broken")
    }

    // ── CSD-065 §2.2: the anyone-may-propose path (CIRISServer#665) ──

    private val selfKey = """{"key_id":"me-1"}"""

    @Test
    fun a_node_without_the_reports_route_says_so_and_nothing_is_filed() = withNode(
        serving(
            mapOf(
                "/v1/federation/self-key-record" to (200 to selfKey),
                "/v1/safety/age-assurance/me-1" to (200 to """{"key_id":"me-1","assurance":{"band":"adult","level":"self"}}"""),
            ),
            fallback = 404 to "{}",
        ),
    ) { client ->
        val vm = ModerationViewModel(client)
        vm.submit("msg-42", ModerationAction.REPORT, "spam")
        awaitThat("proposal outcome") { !vm.state.value.isSubmitting }
        val s = vm.state.value
        assertTrue(s.notOnThisNode)
        assertNull(s.error, "an absent route is a fact about the node, not a failed submit")
        assertFalse(s.isDone)
    }

    @Test
    fun a_recorded_minor_is_refused_before_any_request() = withNode(
        serving(
            mapOf(
                "/v1/federation/self-key-record" to (200 to selfKey),
                "/v1/safety/age-assurance/me-1" to (200 to """{"key_id":"me-1","assurance":{"band":"minor","level":"self"}}"""),
                "/v1/safety/reports" to (200 to """{"contribution_id":"c-1"}"""),
            ),
        ),
    ) { client ->
        val vm = ModerationViewModel(client)
        vm.submit("msg-42", ModerationAction.REPORT, null)
        awaitThat("proposal outcome") { !vm.state.value.isSubmitting }
        assertTrue(vm.state.value.refusedAsMinor)
        assertFalse(vm.state.value.isDone)
        assertFalse(hits.any { it == "POST /v1/safety/reports" }, "refused HERE: no request left the device")
    }

    @Test
    fun no_age_record_is_not_a_refusal() = withNode(
        serving(
            mapOf(
                "/v1/federation/self-key-record" to (200 to selfKey),
                "/v1/safety/age-assurance/me-1" to (200 to """{"key_id":"me-1","assurance":null}"""),
                "/v1/safety/reports" to (200 to """{"contribution_id":"c-1","window_closes_at":"2026-09-29T00:00:00Z"}"""),
            ),
        ),
    ) { client ->
        val vm = ModerationViewModel(client)
        vm.submit("msg-42", ModerationAction.QUESTION, null)
        awaitThat("proposal outcome") { !vm.state.value.isSubmitting }
        assertTrue(vm.state.value.isDone)
        assertEquals("c-1", vm.state.value.result?.contributionId)
        assertTrue(hits.any { it == "POST /v1/safety/reports" })
    }
}
