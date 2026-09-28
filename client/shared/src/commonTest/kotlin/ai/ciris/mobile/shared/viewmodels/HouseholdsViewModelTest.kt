package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.HouseholdsApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.federation.Contact
import ai.ciris.mobile.shared.models.federation.FamilyChangeCarry
import ai.ciris.mobile.shared.models.federation.FamilyChangeProposal
import ai.ciris.mobile.shared.models.federation.FamilyCosignResponse
import ai.ciris.mobile.shared.models.federation.FamilyDto
import ai.ciris.mobile.shared.models.federation.FamilyListResponse
import ai.ciris.mobile.shared.models.federation.FamilyMemberDto
import ai.ciris.mobile.shared.models.federation.FamilySignatureDto
import ai.ciris.mobile.shared.ui.screens.HouseholdAct
import ai.ciris.mobile.shared.ui.screens.ProtocolChoice
import ai.ciris.mobile.shared.ui.screens.ReadFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ME = "me-fed-id"
private const val BO = "bo-fed-id"
private const val CY = "cy-fed-id"

private fun family(
    id: String,
    protocol: String = "founder_only",
    myRole: String = "founder",
    members: List<Pair<String, String>> = listOf(ME to "founder", BO to "member"),
) = FamilyDto(
    familyId = id, name = id, consensusProtocol = protocol, myRole = myRole,
    members = members.map { (k, r) -> FamilyMemberDto(k, r) },
)

/** A node that answers what it is told to, and records every write it was asked for. */
private class FakeHouseholds(
    var pages: List<FamilyListResponse> = listOf(FamilyListResponse(listOf(family("family:v1:a")))),
    var listError: Exception? = null,
    var writeError: NodeRefusal? = null,
) : HouseholdsApi {
    val calls = mutableListOf<String>()
    val afters = mutableListOf<String?>()
    var created: Triple<String, String?, List<String>>? = null
    var cosignedWith: List<FamilySignatureDto>? = null

    override suspend fun listFamilies(after: String?): FamilyListResponse {
        afters += after
        listError?.let { throw it }
        val i = if (after == null) 0 else after.removePrefix("p").toInt()
        return pages[i]
    }
    private fun write(call: String) { calls += call; writeError?.let { throw it } }
    override suspend fun createFamily(name: String, consensusProtocol: String?, members: List<String>): FamilyDto {
        write("create"); created = Triple(name, consensusProtocol, members)
        val f = family("family:v1:new")
        pages = listOf(FamilyListResponse(pages.first().families + f))
        return f
    }
    override suspend fun dissolveFamily(familyId: String) = write("dissolve $familyId")
    override suspend fun addMember(familyId: String, keyId: String, role: String?) = write("add $familyId $keyId")
    override suspend fun removeMember(familyId: String, keyId: String) = write("remove $familyId $keyId")
    override suspend fun changeRole(familyId: String, keyId: String, role: String) = write("role $familyId $keyId $role")
    override suspend fun leave(familyId: String) = write("leave $familyId")
    override suspend fun proposeChange(familyId: String, action: String, keyId: String?, role: String?): FamilyChangeProposal {
        write("propose $familyId $action $keyId")
        return FamilyChangeProposal(
            changeEnvelope = buildJsonObject {
                put("family_key_id", JsonPrimitive(familyId)); put("action", JsonPrimitive(action))
                keyId?.let { put("target_key_id", JsonPrimitive(it)) }
            },
            requiredSignatures = 2, signers = listOf(ME, BO, CY),
        )
    }
    override suspend fun cosign(familyId: String, envelope: JsonObject, signatures: List<FamilySignatureDto>): FamilyCosignResponse {
        write("cosign $familyId"); cosignedWith = signatures
        val all = signatures + FamilySignatureDto(ME, "e", "m")
        return FamilyCosignResponse(signatures = all, requiredSignatures = 2, quorumMet = all.size >= 2)
    }
    override suspend fun assemble(familyId: String, envelope: JsonObject, signatures: List<FamilySignatureDto>) =
        write("assemble $familyId ${signatures.size}")
    override suspend fun contacts(): List<Contact> = listOf(Contact(keyId = CY, aliasOverride = "Cy"))
    override suspend fun myKeyId(): String? = ME
}

class HouseholdsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun loaded(api: FakeHouseholds): HouseholdsViewModel = HouseholdsViewModel(api).also { it.load() }

    // ── Reading ──────────────────────────────────────────────────────────

    @Test
    fun everyPageIsReadAndTheFirstHouseholdIsSelected() {
        val api = FakeHouseholds(pages = listOf(
            FamilyListResponse(listOf(family("family:v1:a")), resume = "p1"),
            FamilyListResponse(listOf(family("family:v1:b")), resume = null),
        ))
        val vm = loaded(api)
        val l = assertIs<HouseholdsLoad.Loaded>(vm.load.value)
        assertEquals(listOf("family:v1:a", "family:v1:b"), l.families.map { it.familyId })
        assertEquals(listOf(null, "p1"), api.afters, "resume is sent back as after")
        assertEquals("family:v1:a", vm.selectedId.value)
        vm.select("family:v1:b")
        assertEquals("family:v1:b", vm.selected()?.familyId)
    }

    @Test
    fun aNodeWithoutTheRoutesIsNotAnEmptyHousehold() {
        val vm = loaded(FakeHouseholds(listError = NodeRefusal(null, null, 404)))
        val f = assertIs<HouseholdsLoad.Failed>(vm.load.value)
        assertIs<ReadFailure.NotOnThisNode>(f.failure)
    }

    @Test
    fun aRefusedReadIsAnErrorCarryingTheNodesId() {
        val vm = loaded(FakeHouseholds(listError = NodeRefusal("family.owner_session_required", "sign in", 403)))
        val f = assertIs<HouseholdsLoad.Failed>(vm.load.value)
        assertIs<ReadFailure.Failed>(f.failure)
        assertEquals("family.owner_session_required", f.refusal?.reasonId)
    }

    // ── Governance decides the route ─────────────────────────────────────

    @Test
    fun aFoundersRemovalIsOneCall() {
        val api = FakeHouseholds()
        val vm = loaded(api)
        vm.request(HouseholdAct.Remove(BO, "Bo"))
        assertNotNull(vm.confirming.value, "nothing is sent before the confirm")
        assertTrue(api.calls.isEmpty())
        vm.confirm()
        assertEquals(listOf("remove family:v1:a $BO"), api.calls)
        assertEquals(HouseholdNotice.REMOVED, vm.notice.value)
        assertNull(vm.pending.value)
    }

    @Test
    fun aQuorumRemovalIsAProposalNotACall() {
        val api = FakeHouseholds(pages = listOf(FamilyListResponse(listOf(
            family("family:v1:q", protocol = "quorum:2/3", myRole = "member",
                members = listOf(ME to "member", BO to "founder", CY to "member")),
        ))))
        val vm = loaded(api)
        vm.request(HouseholdAct.Remove(CY, "Cy"))
        vm.confirm()
        assertEquals(listOf("propose family:v1:q remove $CY"), api.calls, "a single-call DELETE would be refused family.quorum_pending")
        val p = assertNotNull(vm.pending.value)
        assertEquals(2, p.required)
        assertEquals(ME, p.proposedBy)
        assertTrue(p.signatures.isEmpty())

        vm.sign()
        assertEquals(emptyList(), api.cosignedWith, "the first signer starts from nothing")
        assertTrue(vm.pending.value!!.signedBy(ME))

        vm.apply()
        assertEquals("assemble family:v1:q 1", api.calls.last())
        assertNull(vm.pending.value, "an applied change is gone")
        assertEquals(HouseholdNotice.APPLIED, vm.notice.value)
    }

    @Test
    fun aMemberOfAFounderOnlyHouseholdSendsNothing() {
        val api = FakeHouseholds(pages = listOf(FamilyListResponse(listOf(family("family:v1:a", myRole = "member")))))
        val vm = loaded(api)
        vm.request(HouseholdAct.Dissolve)
        vm.confirm()
        assertTrue(api.calls.isEmpty(), "the node would refuse family.not_authorized; nothing is offered or sent")
    }

    @Test
    fun leavingAQuorumHouseholdIsStillOneCall() {
        val api = FakeHouseholds(pages = listOf(FamilyListResponse(listOf(
            family("family:v1:q", protocol = "quorum:2/3", myRole = "member",
                members = listOf(ME to "member", BO to "founder", CY to "member")),
        ))))
        val vm = loaded(api)
        vm.request(HouseholdAct.Leave)
        vm.confirm()
        assertEquals(listOf("leave family:v1:q"), api.calls)
    }

    @Test
    fun aRefusalIsKeptByItsId() {
        val api = FakeHouseholds(writeError = NodeRefusal("family.readd_unsupported", "removed", 409))
        val vm = loaded(api)
        vm.request(HouseholdAct.Add(CY, "Cy"))
        vm.confirm()
        assertEquals("family.readd_unsupported", vm.refusal.value?.reasonId)
        assertNull(vm.notice.value)
        assertFalse(vm.busy.value)
    }

    // ── Forming, and changes that arrive as text ─────────────────────────

    @Test
    fun formingSendsTheProtocolOnlyWhenItIsNotTheDefault() {
        val api = FakeHouseholds()
        val vm = loaded(api)
        vm.create("  The Okafors ", ProtocolChoice.FOUNDER_ONLY, emptyList())
        assertEquals(Triple("The Okafors", null, emptyList<String>()), api.created)
        assertEquals("family:v1:new", vm.selectedId.value, "the new household is the one shown")
        vm.create("Two", ProtocolChoice.MAJORITY, listOf(CY))
        assertEquals(Triple("Two", "majority", listOf(CY)), api.created)
    }

    @Test
    fun aPastedChangeForAnotherHouseholdIsRefused() {
        val vm = loaded(FakeHouseholds())
        val foreign = FamilyChangeCarry(buildJsonObject {
            put("family_key_id", JsonPrimitive("family:v1:elsewhere")); put("action", JsonPrimitive("add"))
        })
        assertFalse(vm.importChange(foreign))
        assertFalse(vm.importChange(null))
        // A founder_only household has no quorum to sign for.
        val ours = FamilyChangeCarry(buildJsonObject {
            put("family_key_id", JsonPrimitive("family:v1:a")); put("action", JsonPrimitive("add"))
        })
        assertFalse(vm.importChange(ours))
        assertNull(vm.pending.value)
    }

    @Test
    fun aPastedChangeForAQuorumHouseholdIsSignable() {
        val api = FakeHouseholds(pages = listOf(FamilyListResponse(listOf(
            family("family:v1:q", protocol = "quorum:2/3", myRole = "member",
                members = listOf(ME to "member", BO to "founder", CY to "member")),
        ))))
        val vm = loaded(api)
        val theirs = FamilyChangeCarry(
            buildJsonObject { put("family_key_id", JsonPrimitive("family:v1:q")); put("action", JsonPrimitive("dissolve")) },
            listOf(FamilySignatureDto(BO, "e", "m")),
        )
        assertTrue(vm.importChange(theirs))
        val p = assertNotNull(vm.pending.value)
        assertEquals(listOf(ME, BO, CY), p.signers)
        assertNull(p.proposedBy)
        vm.sign()
        assertEquals(listOf(FamilySignatureDto(BO, "e", "m")), api.cosignedWith, "their signature travels with the change")
        assertTrue(vm.pending.value!!.quorumMet)
    }
}
