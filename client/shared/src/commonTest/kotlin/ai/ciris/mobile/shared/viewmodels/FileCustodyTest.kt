package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.DriveApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.chat.CegChatMessage
import ai.ciris.mobile.shared.models.drive.CopiesLine
import ai.ciris.mobile.shared.models.drive.CustodyDevice
import ai.ciris.mobile.shared.models.drive.CustodyWhy
import ai.ciris.mobile.shared.models.drive.WhyLines
import ai.ciris.mobile.shared.models.drive.WHY_INLINE_NO_RECEIPT
import ai.ciris.mobile.shared.models.drive.WHY_NO_COPY_HERE
import ai.ciris.mobile.shared.models.drive.WHY_RECEIPT_IS_DELIVERY
import ai.ciris.mobile.shared.models.drive.custodyWhyHas
import ai.ciris.mobile.shared.models.drive.custodyWhyLines
import ai.ciris.mobile.shared.models.drive.CustodySummary
import ai.ciris.mobile.shared.models.drive.DriveListing
import ai.ciris.mobile.shared.models.drive.FileCustody
import ai.ciris.mobile.shared.models.drive.FileWrite
import ai.ciris.mobile.shared.models.drive.FileWritten
import ai.ciris.mobile.shared.models.drive.Holds
import ai.ciris.mobile.shared.models.drive.MediaPolicy
import ai.ciris.mobile.shared.models.drive.NoteListing
import ai.ciris.mobile.shared.models.drive.OpenedFile
import ai.ciris.mobile.shared.models.drive.custodyCopies
import ai.ciris.mobile.shared.models.drive.custodyHolds
import ai.ciris.mobile.shared.models.drive.custodyReceiptsUnsupported
import ai.ciris.mobile.shared.models.drive.custodySummary
import ai.ciris.mobile.shared.models.drive.custodyWhyKey
import ai.ciris.mobile.shared.models.drive.isFileContentType
import ai.ciris.mobile.shared.ui.screens.chatCustodyTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CSD-107: the custody card's wire and its rendering decisions, as pure
 * functions. The wire is PROVISIONAL (a server PR in progress), so the parse
 * is tested for what it must survive as much as for what it reads.
 */
class FileCustodyTest {
    private fun parse(s: String) = FileCustody.fromWire(Json.parseToJsonElement(s).jsonObject)

    /** The CSD-107 topology's actor view: D1 authored it, D2 receipted it. */
    private val full = """
        {"devices_total":2,
         "devices":[
           {"node_key_id":"D1","label":"Laptop","this_device":true,"can_open":true,"received":null,"holds":"here"},
           {"node_key_id":"D2","this_device":false,"can_open":true,"received":{"epoch":3,"k":1,"at":"2026-09-30T10:11:12Z"},"holds":"received"}],
         "held_here":true,"copies_known":2,"copies_observable":false,"announced_holders":[],
         "access":[{"person_key_id":"P","devices":["D1","D2"],"via":"at_rest_grant"}],
         "author_device":"D1","this_device_is_author":true,
         "receipts_supported":true,"receipts_unsupported_reason":null,"receipts_from_other_keys":[{"key_id":"X"}],
         "why":[{"reason_id":"custody.copies_unobservable_by_design","detail":"Your own and your family's files are never announced."},
                {"reason_id":"custody.receipt_is_delivery_not_holding","detail":"A delivery receipt proves a device received the whole file."}],
         "something_new":{"a":1}}
    """

    @Test
    fun theFullPlannedShapeParses() {
        val c = parse(full)
        assertEquals(2, c.devicesTotal)
        assertEquals(listOf("D1", "D2"), c.devices.map { it.nodeKeyId })
        assertEquals("Laptop", c.devices[0].label)
        assertNull(c.devices[1].label)
        assertTrue(c.devices[0].thisDevice)
        assertEquals(true, c.devices[1].canOpen)
        assertNull(c.devices[0].received)
        assertEquals(3L, c.devices[1].received?.epoch)
        assertEquals(1, c.devices[1].received?.k)
        assertEquals("2026-09-30T10:11:12Z", c.devices[1].received?.at)
        assertEquals(true, c.heldHere)
        assertEquals(2, c.copiesKnown)
        assertEquals(false, c.copiesObservable)
        assertEquals(0, c.announcedHolders, "a list at #704: its size")
        assertEquals(1, c.accessCount)
        assertEquals("D1", c.authorDevice)
        assertEquals(true, c.thisDeviceIsAuthor)
        assertEquals(1, c.receiptsFromOtherKeys)
        assertNull(c.receiptsUnsupportedReason)
        assertEquals(true, c.receiptsSupported)
        assertEquals(listOf("custody.copies_unobservable_by_design", "custody.receipt_is_delivery_not_holding"), c.why.map { it.reasonId })
        assertTrue(custodyWhyHas(c, WHY_RECEIPT_IS_DELIVERY))
        assertFalse(custodyWhyHas(c, WHY_INLINE_NO_RECEIPT))
    }

    @Test
    fun aPartialOrRetypedBodyReadsAsAbsentNeverAsACrash() {
        // Field names may still change: an empty body, a missing list, a string
        // where a bool was planned — each is "not sent", not an exception.
        val empty = parse("{}")
        assertNull(empty.devicesTotal)
        assertTrue(empty.devices.isEmpty())
        assertNull(empty.heldHere)
        assertNull(empty.copiesObservable)
        assertNull(empty.receiptsSupported)

        val odd = parse("""{"devices_total":"two","held_here":"yes","devices":[{"holds":"here"},7,{"node_key_id":5}],"why":[1,"ok",""]}""")
        assertNull(odd.devicesTotal)
        assertNull(odd.heldHere)
        assertEquals(2, odd.devices.size, "a non-object device entry is dropped")
        assertNull(odd.devices[1].nodeKeyId, "a numeric key id is not a key id")
        assertEquals(listOf(CustodyWhy(null, "ok")), odd.why, "a pre-#704 bare sentence is a detail; a number and a blank are dropped")
    }

    @Test
    fun theActorViewNamesD2AsReceivedAndCountsTwoDevices() {
        val c = parse(full)
        assertEquals(Holds.Here, custodyHolds(c.devices[0], c.receiptsSupported))
        assertEquals(Holds.Received("2026-09-30T10:11:12Z"), custodyHolds(c.devices[1], c.receiptsSupported))
        assertEquals(CustodySummary.Exactly(on = 2, total = 2), custodySummary(c))
    }

    @Test
    fun receiptsUnsupportedMakesHoldsUnknownNeverNotOn() {
        // Inline files (<= 1 MiB) carry no receipts until persist v52 / edge v38.
        val c = FileCustody(
            devicesTotal = 2,
            devices = listOf(
                CustodyDevice("D1", thisDevice = true, holds = "here"),
                CustodyDevice("D2", holds = "received"), // cannot be true when receipts are unsupported
            ),
            receiptsSupported = false,
        )
        assertEquals(Holds.Here, custodyHolds(c.devices[0], false))
        assertEquals(Holds.Unknown, custodyHolds(c.devices[1], false))
        assertEquals(Holds.Unknown, custodyHolds(CustodyDevice("D3", holds = "not_here"), true), "no token means 'not on'")
        assertEquals(Holds.Unknown, custodyHolds(CustodyDevice("D4"), null))
        assertTrue(custodyReceiptsUnsupported(c))
        assertFalse(custodyReceiptsUnsupported(c.copy(receiptsSupported = null)), "absent is not false")
        assertEquals(CustodySummary.AtLeast(on = 1, total = 2, unknown = 1), custodySummary(c))
    }

    @Test
    fun devicesCountedButNotListedAreUnknownNotOff() {
        val c = FileCustody(devicesTotal = 3, devices = listOf(CustodyDevice("D1", holds = "here")))
        assertEquals(CustodySummary.AtLeast(on = 1, total = 3, unknown = 2), custodySummary(c))
        assertEquals(CustodySummary.NoDevices, custodySummary(FileCustody(devicesTotal = 0)))
        assertEquals(CustodySummary.NoDevices, custodySummary(FileCustody()))
    }

    @Test
    fun copiesAreCountedOnlyWhenObservable() {
        // Self and family files: copies_observable=false by design (CC 5.2). The
        // count the node CAN see must never be shown as the number of copies.
        assertEquals(CopiesLine.NotObservable, custodyCopies(FileCustody(copiesKnown = 1, copiesObservable = false)))
        assertEquals(CopiesLine.NotObservable, custodyCopies(FileCustody(copiesKnown = 1)), "not said is not observable")
        assertEquals(CopiesLine.Known(4), custodyCopies(FileCustody(copiesKnown = 4, copiesObservable = true)))
        assertEquals(CopiesLine.NotSent, custodyCopies(FileCustody(copiesObservable = true)))
    }

    @Test
    fun noneIsTheOnlyNotOnAndItCountsAsKnown() {
        // `holds: "none"` (#704 d1a15286): the device said it has no copy. The
        // only token that renders "not on this device"; unknown never does.
        val c = parse("""{"devices_total":3,"receipts_supported":true,"devices":[
            {"node_key_id":"D1","this_device":true,"holds":"none","can_open":null,"checked_at":"2026-09-30T12:00:00Z"},
            {"node_key_id":"D2","holds":"received","received":{"epoch":0,"k":25,"at":null},"reported_at":null},
            {"node_key_id":"D3","holds":"none","reported_at":"2026-10-02T08:00:00Z"}]}""")
        assertEquals(Holds.None, custodyHolds(c.devices[0], true))
        assertEquals(Holds.None, custodyHolds(c.devices[2], true))
        assertEquals(Holds.Unknown, custodyHolds(CustodyDevice("D4", holds = "unknown"), true))
        assertEquals(CustodySummary.Exactly(on = 1, total = 3), custodySummary(c), "a said-none device is known, not unknown")
        assertNull(c.devices[0].canOpen, "can_open null is 'can't tell', not no")
        assertEquals("2026-09-30T12:00:00Z", c.devices[0].checkedAt)
        assertNull(c.devices[1].reportedAt)
        assertEquals("2026-10-02T08:00:00Z", c.devices[2].reportedAt)
    }

    @Test
    fun aNoCopyHereAnswerIsACardNotAnError() {
        // On a device with the row and not the bytes the view answers 200:
        // access, size_bytes and can_open are null, and the node says why.
        val c = parse("""{"attestation_id":"a","cohort":"self","tier":"invisible_encrypted","size_bytes":null,
            "author_device":"D1","this_device_is_author":false,"checked_at":"2026-09-30T12:00:00Z",
            "devices_total":2,"devices":[
              {"node_key_id":"D1","label":"laptop","this_device":false,"can_open":null,"received":null,"holds":"unknown","reported_at":null},
              {"node_key_id":"D2","this_device":true,"can_open":null,"received":null,"holds":"none","checked_at":"2026-09-30T12:00:00Z","reported_at":null}],
            "held_here":false,"copies_known":0,"copies_observable":false,"announced_holders":[],"access":null,
            "receipts_supported":true,"receipts_unsupported_reason":null,"receipts_from_other_keys":[],
            "why":[{"reason_id":"custody.no_copy_here","detail":"This device holds no copy of the file."},
                   {"reason_id":"custody.receipts_admitted_on_author_device","detail":"Delivery receipts are collected by the device that wrote the file."},
                   {"reason_id":"custody.no_copy_reports_pending","detail":"Your other devices cannot yet report that they hold no copy."}]}""")
        assertNull(c.accessCount)
        assertNull(c.sizeBytes)
        assertEquals(false, c.heldHere)
        assertEquals(false, c.thisDeviceIsAuthor)
        assertEquals("2026-09-30T12:00:00Z", c.checkedAt)
        assertTrue(custodyWhyHas(c, WHY_NO_COPY_HERE))
        assertEquals(CustodySummary.AtLeast(on = 0, total = 2, unknown = 1), custodySummary(c))
        assertEquals(CopiesLine.NotObservable, custodyCopies(c), "copies_known 0 is never shown as 'no copies' for a self file")
    }

    @Test
    fun aReceiptWithNoTimeIsReceivedWithNoTime() {
        // `received.at` is null until persist v52: "received", never a blank or an epoch.
        val c = parse("""{"receipts_supported":true,"devices":[{"node_key_id":"D2","received":{"epoch":0,"k":25,"at":null},"holds":"received"}]}""")
        assertEquals(Holds.Received(null), custodyHolds(c.devices[0], c.receiptsSupported))
    }

    @Test
    fun aReasonIdIsLocalizedWithItsDetailAsSmallPrintAndFallsBackToDetailThenId() {
        val bundle = mapOf("custody.inline_no_receipt" to "Small files carry no receipt yet.")
        val lookup: (String) -> String = { k -> bundle[k] ?: k }
        assertEquals(
            WhyLines("Small files carry no receipt yet.", "node english"),
            custodyWhyLines(CustodyWhy("custody.inline_no_receipt", "node english"), lookup),
        )
        assertEquals(WhyLines("node english", null), custodyWhyLines(CustodyWhy("custody.brand_new", "node english"), lookup))
        assertEquals(WhyLines("custody.brand_new", null), custodyWhyLines(CustodyWhy("custody.brand_new", null), lookup))
        assertEquals(WhyLines("a sentence", null), custodyWhyLines(CustodyWhy(null, "a sentence"), lookup))
    }

    @Test
    fun whyEntriesThatLookLikeIdsAreLookedUpAndSentencesAreShownVerbatim() {
        assertEquals("custody.self_copies_unobservable", custodyWhyKey("custody.self_copies_unobservable"))
        assertEquals("drive.not_fetched", custodyWhyKey(" drive.not_fetched "))
        assertNull(custodyWhyKey("Receipts arrive from each device."))
        assertNull(custodyWhyKey("inline"), "an undotted word is not an id")
        assertNull(custodyWhyKey("Custody.X"))
    }

    @Test
    fun aChatRowIsAFileOnlyWhenItsContentIsNotText() {
        assertFalse(isFileContentType("text/plain"))
        assertFalse(isFileContentType("text/markdown; charset=utf-8"))
        assertFalse(isFileContentType(""))
        assertFalse(isFileContentType(null))
        assertTrue(isFileContentType("image/png"))
        assertTrue(isFileContentType("application/pdf"))

        val text = CegChatMessage(attestationId = "m1", communityId = "room-1")
        assertNull(chatCustodyTarget(text), "today's chat rows are text: no menu item")
        val file = text.copy(attestationId = "m2", contentType = "image/png")
        assertEquals(CustodyTarget("m2", "community", "room-1", null), chatCustodyTarget(file))
    }

    @Test
    fun aSelfFileNamesNoRoomOnTheQuery() {
        assertNull(CustodyTarget("a", "self", "me", "x").queryRoom)
        assertEquals("fam", CustodyTarget("a", "family", "fam", "x").queryRoom)
        assertNull(CustodyTarget("a", "community", " ", "x").queryRoom)
    }
}

/** A drive that only answers custody. */
private class CustodyDrive(var answer: () -> FileCustody) : DriveApi {
    val asked = mutableListOf<Triple<String, String, String?>>()
    override suspend fun readCustody(attestationId: String, cohort: String, roomId: String?): FileCustody {
        asked += Triple(attestationId, cohort, roomId)
        return answer()
    }
    override suspend fun readDrive(cohort: String?, roomId: String?, limit: Int): DriveListing = error("unused")
    override suspend fun readFile(attestationId: String, roomId: String): OpenedFile = error("unused")
    override suspend fun writeFile(write: FileWrite): FileWritten = error("unused")
    override suspend fun readNotes(): NoteListing = error("unused")
    override suspend fun writeNote(body: String) = error("unused")
    override suspend fun readMediaPolicy(): MediaPolicy = error("unused")
}

class FileCustodyViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private val target = CustodyTarget("att-1", "family", "fam-1", "a.txt")

    @Test
    fun aBare404IsTheVersionFactNotAnError() {
        val vm = FileCustodyViewModel(CustodyDrive { throw NodeRefusal(null, null, 404) })
        vm.open(target)
        assertIs<CustodyState.NodeTooOld>(vm.state.value)
    }

    @Test
    fun a404WithAnIdIsARefusalByName() {
        val vm = FileCustodyViewModel(CustodyDrive { throw NodeRefusal("drive.not_in_room", "no such file", 404) })
        vm.open(target)
        val failed = assertIs<CustodyState.Failed>(vm.state.value)
        assertEquals("drive.not_in_room", failed.reasonId)
    }

    @Test
    fun anAnswerIsReadyAndTheQueryCarriesCohortAndRoom() {
        val drive = CustodyDrive { FileCustody(devicesTotal = 1) }
        val vm = FileCustodyViewModel(drive)
        vm.open(target)
        assertIs<CustodyState.Ready>(vm.state.value)
        assertEquals(listOf<Triple<String, String, String?>>(Triple("att-1", "family", "fam-1")), drive.asked)
        vm.open(CustodyTarget("n1", "self", "me", null))
        assertEquals<Triple<String, String, String?>>(Triple("n1", "self", null), drive.asked.last(), "a self file names no room")
        vm.close()
        assertEquals<CustodyState>(CustodyState.Closed, vm.state.value)
    }

    @Test
    fun aTransportFailureIsAnErrorNeverEmpty() {
        val vm = FileCustodyViewModel(CustodyDrive { throw IllegalStateException("connection refused") })
        vm.open(target)
        val failed = assertIs<CustodyState.Failed>(vm.state.value)
        assertNull(failed.reasonId)
        assertEquals("connection refused", failed.detail)
    }
}
