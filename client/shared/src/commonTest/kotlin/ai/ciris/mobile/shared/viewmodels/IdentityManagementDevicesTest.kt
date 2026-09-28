package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.api.SelfDevicesApi
import ai.ciris.mobile.shared.api.SystemWarning
import ai.ciris.mobile.shared.models.federation.LabelOccurrenceResponse
import ai.ciris.mobile.shared.models.federation.OwnedNodeDto
import ai.ciris.mobile.shared.models.federation.OwnedNodesDto
import ai.ciris.mobile.shared.models.federation.ReleaseNodeResponse
import ai.ciris.mobile.shared.models.federation.SelfOccurrence
import ai.ciris.mobile.shared.models.federation.SelfOccurrencesResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val OWNER = "owner-fed-id"
private const val THIS_NODE = "node-here"
private const val OTHER_NODE = "node-there"

/** A node that answers what it is told to, and records what it was asked. */
private class FakeDevices(
    var rows: List<SelfOccurrence> = emptyList(),
    var labelError: NodeRefusal? = null,
    /** Refusals keyed on force_self: what the node says to a release sent with/without force. */
    var releaseError: (nodeKeyId: String, force: Boolean) -> NodeRefusal? = { _, _ -> null },
) : SelfDevicesApi {
    val labels = mutableListOf<Pair<String, String>>()
    val releases = mutableListOf<Pair<String, Boolean>>()
    var nodes = listOf(OwnedNodeDto(THIS_NODE, isSelf = true), OwnedNodeDto(OTHER_NODE))

    override suspend fun ownedNodes() = OwnedNodesDto(owner = OWNER, nodes = nodes)
    override suspend fun selfKeyId() = "node-signer"
    override suspend fun occurrences(identityKeyId: String) = SelfOccurrencesResponse(identityKeyId, rows)
    override suspend fun labelOccurrence(occurrenceKeyId: String, label: String): LabelOccurrenceResponse {
        labels += occurrenceKeyId to label
        labelError?.let { throw it }
        rows = rows.map { if (it.occurrenceKeyId == occurrenceKeyId) it.copy(label = label.trim()) else it }
        return LabelOccurrenceResponse(occurrenceKeyId, label.trim(), "att-1")
    }
    override suspend fun releaseNode(nodeKeyId: String, forceSelf: Boolean): ReleaseNodeResponse {
        releases += nodeKeyId to forceSelf
        releaseError(nodeKeyId, forceSelf)?.let { throw it }
        nodes = nodes.filterNot { it.keyId == nodeKeyId }
        return ReleaseNodeResponse(nodeKeyId, released = true, releasedSelf = nodeKeyId == THIS_NODE)
    }
    override suspend fun nodeWarnings(): List<SystemWarning> = emptyList()
}

private fun row(id: String, revoked: Boolean? = false, label: String? = null) =
    SelfOccurrence(occurrenceKeyId = id, revoked = revoked, label = label)

/** The real client, pointed at a port nothing listens on. The fake must answer every call. */
private fun deadClient() = CIRISApiClient(baseUrl = "http://127.0.0.1:9")

class IdentityManagementDevicesTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun vm(devices: FakeDevices) = IdentityManagementViewModel(deadClient(), "http://127.0.0.1:9", devices)

    // ── The wire ──────────────────────────────────────────────────────────

    @Test
    fun anOlderNodesRowStillParsesAndSaysNothingAboutRevocation() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(
            SelfOccurrencesResponse.serializer(),
            """{"identity_key_id":"o","occurrences":[{"occurrence_key_id":"a","device_class":"phone","has_encryption_pubkeys":true,"asserted_at":"t"}]}""",
        )
        assertNull(old.occurrences.single().revoked, "an older node's row must not read as active")
        assertNull(old.occurrences.single().label)

        val new = json.decodeFromString(
            SelfOccurrencesResponse.serializer(),
            """{"identity_key_id":"o","occurrences":[{"occurrence_key_id":"a","device_class":"phone","has_encryption_pubkeys":true,"asserted_at":"t","revoked":true,"label":"Mira's phone"}]}""",
        )
        assertEquals(true, new.occurrences.single().revoked)
        assertEquals("Mira's phone", new.occurrences.single().label)
    }

    // ── The roster ────────────────────────────────────────────────────────

    @Test
    fun revokedDevicesAreListedAsRevokedWithTheirLabels() {
        val vm = vm(FakeDevices(rows = listOf(row("a", label = "Laptop"), row("b", revoked = true, label = "Old phone"))))
        val listed = vm.occurrences.value
        assertEquals(listOf("a", "b"), listed.map { it.occurrenceKeyId }, "a revoked device is not hidden")
        assertEquals(listOf(false, true), listed.map { it.revoked })
        assertEquals(listOf("Laptop", "Old phone"), listed.map { it.label })
        assertFalse(vm.devicesUnsupported.value)
    }

    @Test
    fun aNodeWhoseRowsCarryNoRevokedIsTooOldAndSaysSo() {
        val vm = vm(FakeDevices(rows = listOf(row("a", revoked = null))))
        assertTrue(vm.devicesUnsupported.value, "rows without `revoked` come from a pre-0.5.216 node")
    }

    @Test
    fun anEmptyRosterSaysNothingAboutTheNodesVersion() {
        val vm = vm(FakeDevices(rows = emptyList()))
        assertFalse(vm.devicesUnsupported.value)
    }

    // ── Naming a device ───────────────────────────────────────────────────

    @Test
    fun savingANameSendsItAndTheRosterShowsIt() {
        val devices = FakeDevices(rows = listOf(row("a")))
        val vm = vm(devices)
        vm.startLabel("a")
        vm.saveLabel("  Kitchen tablet ")
        assertEquals(listOf("a" to "  Kitchen tablet "), devices.labels)
        assertNull(vm.labelling.value, "the editor closes on success")
        assertEquals("Kitchen tablet", vm.occurrences.value.single().label)
    }

    @Test
    fun aLabelRefusalIsKeptByName() {
        val devices = FakeDevices(
            rows = listOf(row("a")),
            labelError = NodeRefusal("self.not_your_device", "that key is not one of your devices", 404),
        )
        val vm = vm(devices)
        vm.startLabel("a")
        vm.saveLabel("x")
        assertEquals("self.not_your_device", vm.labelRefusal.value?.reasonId)
        assertEquals("a", vm.labelling.value, "a refused save keeps the editor open")
        assertFalse(vm.devicesUnsupported.value, "a 404 WITH an id is a refusal, not an old node")
    }

    @Test
    fun aBare404OnLabelMeansTheNodeIsTooOld() {
        val vm = vm(FakeDevices(rows = listOf(row("a")), labelError = NodeRefusal(null, null, 404)))
        vm.startLabel("a")
        vm.saveLabel("x")
        assertTrue(vm.devicesUnsupported.value)
        assertNull(vm.labelRefusal.value)
        assertNull(vm.labelling.value)
    }

    // ── Releasing a node ──────────────────────────────────────────────────

    @Test
    fun releaseAsksFirstAndSendsNoForce() {
        val devices = FakeDevices()
        val vm = vm(devices)
        vm.askRelease(OTHER_NODE)
        assertIs<ReleaseState.Confirming>(vm.release.value)
        assertTrue(devices.releases.isEmpty(), "nothing is sent before the confirm")
        vm.confirmRelease()
        assertEquals(listOf(OTHER_NODE to false), devices.releases)
        assertEquals(ReleaseState.Released(OTHER_NODE, releasedSelf = false), vm.release.value)
        assertEquals(listOf(THIS_NODE), vm.ownedNodes.value.map { it.keyId }, "the list is re-read after a release")
    }

    @Test
    fun releasingThisNodeExplainsAndForcesOnlyAfterASecondConfirm() {
        val devices = FakeDevices(releaseError = { node, force ->
            if (node == THIS_NODE && !force) {
                NodeRefusal("self.release_self_requires_force", "that is the node you are talking to", 409)
            } else {
                null
            }
        })
        val vm = vm(devices)
        vm.askRelease(THIS_NODE)
        vm.confirmRelease()
        val needs = assertIs<ReleaseState.NeedsForce>(vm.release.value)
        assertEquals("self.release_self_requires_force", needs.refusal.reasonId)
        assertEquals(listOf(THIS_NODE to false), devices.releases, "the refusal did not trigger a forced retry")

        // Confirming again from here does nothing: only the explicit second confirm forces.
        vm.confirmRelease()
        vm.confirmForceRelease()
        assertEquals(1, devices.releases.size)

        vm.askForceRelease()
        assertIs<ReleaseState.ConfirmingForce>(vm.release.value)
        assertEquals(1, devices.releases.size, "asking for force is not yet forcing")
        vm.confirmForceRelease()
        assertEquals(listOf(THIS_NODE to false, THIS_NODE to true), devices.releases)
        assertEquals(ReleaseState.Released(THIS_NODE, releasedSelf = true), vm.release.value)
    }

    @Test
    fun cancellingTheSecondConfirmForcesNothing() {
        val devices = FakeDevices(releaseError = { _, force ->
            if (!force) NodeRefusal("self.release_self_requires_force", null, 409) else null
        })
        val vm = vm(devices)
        vm.askRelease(THIS_NODE)
        vm.confirmRelease()
        vm.askForceRelease()
        vm.cancelRelease()
        vm.confirmForceRelease()
        assertEquals(listOf(THIS_NODE to false), devices.releases)
        assertEquals(ReleaseState.Idle, vm.release.value)
    }

    @Test
    fun anIncompleteReleaseIsARefusalByNameAndTheListIsReRead() {
        val devices = FakeDevices(releaseError = { _, _ ->
            NodeRefusal("self.release_incomplete", "the release was signed but the node is still listed", 500)
        })
        val vm = vm(devices)
        devices.nodes = listOf(OwnedNodeDto(OTHER_NODE))
        vm.askRelease(OTHER_NODE)
        vm.confirmRelease()
        val refused = assertIs<ReleaseState.Refused>(vm.release.value)
        assertEquals("self.release_incomplete", refused.refusal.reasonId)
        assertEquals(listOf(OTHER_NODE), vm.ownedNodes.value.map { it.keyId }, "what the node now lists is shown, not assumed")
    }

    @Test
    fun aBare404OnReleaseMeansTheNodeIsTooOld() {
        val vm = vm(FakeDevices(releaseError = { _, _ -> NodeRefusal(null, null, 404) }))
        vm.askRelease(OTHER_NODE)
        vm.confirmRelease()
        assertTrue(vm.devicesUnsupported.value)
        assertEquals(ReleaseState.Idle, vm.release.value)
    }
}
