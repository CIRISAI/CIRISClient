package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.DelegationBodies
import ai.ciris.mobile.shared.api.DelegationsApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.federation.CreateDelegationResponse
import ai.ciris.mobile.shared.models.federation.DelegationConstraints
import ai.ciris.mobile.shared.models.federation.DelegationDto
import ai.ciris.mobile.shared.models.federation.DelegationsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A node that answers what it is told to, and records what it was asked (CSD-055). */
private class FakeGrants(
    var grants: List<DelegationDto> = emptyList(),
    var listError: NodeRefusal? = null,
) : DelegationsApi {
    val denied = mutableListOf<String>()
    val revoked = mutableListOf<String>()
    override suspend fun list(): List<DelegationDto> {
        listError?.let { throw it }
        return grants
    }
    override suspend fun create(label: String, mode: String, existingKeyId: String?, constraints: DelegationConstraints?) =
        CreateDelegationResponse(claimUrl = "/v1/auth/device/claim", pin = "ABCD-1234", clientId = label)
    override suspend fun approve(userCode: String, constraints: DelegationConstraints?) = Unit
    override suspend fun deny(userCode: String) {
        denied += userCode
    }
    override suspend fun revoke(clientId: String) {
        revoked += clientId
        grants = grants.filterNot { it.clientId == clientId }
    }
}

class DelegationsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun vm(api: FakeGrants) = DelegationsViewModel(CIRISApiClient(baseUrl = "http://127.0.0.1:9"), api)
    private val grant = DelegationDto(clientId = "wa-agent-7c31", scope = "owner:act-on-behalf", attestationId = "att-1")

    @Test
    fun aListThatFailedToLoadIsNotAnEmptyList() {
        val api = FakeGrants(listError = NodeRefusal(null, "store unavailable", 503))
        val m = vm(api)
        m.refresh()
        assertTrue(m.delegations.value.isEmpty())
        assertNotNull(m.listError.value, "a failed read must be said, not rendered as 'no delegations'")
        api.listError = null
        api.grants = listOf(grant)
        m.refresh()
        assertNull(m.listError.value)
        assertEquals(listOf(grant), m.delegations.value)
    }

    @Test
    fun aRevokeWaitsForTheConfirmAndThenWithdrawsThatGrant() {
        val api = FakeGrants(grants = listOf(grant))
        val m = vm(api)
        m.refresh()
        m.askRevoke(grant)
        assertEquals(grant, m.pendingRevoke.value)
        assertTrue(api.revoked.isEmpty(), "asking must not revoke")
        m.cancelRevoke()
        assertTrue(api.revoked.isEmpty())
        m.askRevoke(grant)
        m.confirmRevoke()
        assertEquals(listOf("wa-agent-7c31"), api.revoked)
        assertNull(m.pendingRevoke.value)
        assertTrue(m.delegations.value.isEmpty())
    }

    @Test
    fun theOwnerCanRefuseACode() {
        val api = FakeGrants()
        val m = vm(api)
        m.deny("   ")
        assertTrue(api.denied.isEmpty())
        assertNotNull(m.error.value)
        m.deny(" WDJB-MJHT ")
        assertEquals(listOf("WDJB-MJHT"), api.denied)
        assertNull(m.error.value)
        assertNotNull(m.notice.value)
    }

    @Test
    fun anOwnerSessionFailureSaysSignInWhateverTheNodesSentence() {
        val msg = DelegationsViewModel.failure(NodeRefusal(null, "missing bearer session token", 401), "revoke")
        assertEquals("Sign in as the owner first.", msg)
    }

    @Test
    fun theSignedRecordIdIsKept() {
        val json = Json { ignoreUnknownKeys = true }
        val r = json.decodeFromString(
            DelegationsResponse.serializer(),
            """{"grants":[{"client_id":"a","scope":"owner:act-on-behalf","attestation_id":"att-9"}]}""",
        )
        assertEquals("att-9", r.grants.single().attestationId)
    }

    @Test
    fun whatTheOwnerTypedCannotAddTermsToTheGrant() {
        val body = Json.parseToJsonElement(
            DelegationBodies.delegate(
                label = "x\",\"sub_delegation\":true,\"y\":\"",
                mode = "create",
                existingKeyId = null,
                scope = listOf("owner:act-on-behalf"),
                constraints = DelegationConstraints(actionsAllow = emptyList(), goal = "say \"hi\""),
            ),
        ).jsonObject
        assertFalse("sub_delegation" in body, "a quote in the label must not become a field")
        assertEquals("x\",\"sub_delegation\":true,\"y\":\"", body["label"]!!.jsonPrimitive.content)
        val c = body["constraints"]!!.jsonObject
        assertEquals(0, c["actions_allow"]!!.jsonArray.size, "read-only stays an explicit []")
        assertEquals("say \"hi\"", c["goal"]!!.jsonPrimitive.content)
    }
}
