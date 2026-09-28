package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.api.RouteNotOnThisHost
import ai.ciris.mobile.shared.models.federation.CommunityRoom
import ai.ciris.mobile.shared.models.federation.CommunityRoomMember
import ai.ciris.mobile.shared.models.federation.Contact
import ai.ciris.mobile.shared.ui.screens.CommunityTags
import ai.ciris.mobile.shared.ui.screens.protocolFamily
import ai.ciris.mobile.shared.Screen
import ai.ciris.mobile.shared.screenToSurface
import ai.ciris.mobile.shared.ui.nav.CirclesNav
import ai.ciris.mobile.shared.ui.nav.CohortScope
import ai.ciris.mobile.shared.ui.nav.NavSurface
import ai.ciris.mobile.shared.ui.nav.Tab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The decisions the communities cards make without a socket (CSD-102, CSD-103):
 * which rooms a card of a tier shows, how a failure is read, who a pair room is
 * with, who joined late, and what a paste is.
 */
class CommunitiesLogicTest {

    private fun room(id: String, tier: String, kind: String = "room", name: String = id) =
        CommunityRoom(communityId = id, name = name, kind = kind, tier = tier)

    private val rooms = listOf(
        room("chat:room:v1:b", "community", name = "Bakery"),
        room("chat:pair:v1:p", "community", kind = "pair"),
        room("chat:room:v1:a", "affiliations", name = "Clinic"),
        room("chat:room:v1:c", "community", name = "Allotment"),
    )

    @Test
    fun rulesAndPeopleShowOnlyThisTiersRoomsOfMoreThanTwo() {
        assertEquals(
            listOf("chat:room:v1:c", "chat:room:v1:b"),
            CommunitiesViewModel.roomsFor(rooms, "community", includePairs = false).map { it.communityId },
        )
        assertEquals(
            listOf("chat:room:v1:a"),
            CommunitiesViewModel.roomsFor(rooms, "affiliations", includePairs = false).map { it.communityId },
        )
    }

    /** A pair room is `tier: community` on the wire, so it is a Neighbours chat — and never an affiliations one. */
    @Test
    fun chatsShowPairRoomsAtTheCommunityTierOnly() {
        val neighbours = CommunitiesViewModel.roomsFor(rooms, "community", includePairs = true).map { it.communityId }
        assertEquals(listOf("chat:room:v1:c", "chat:room:v1:b", "chat:pair:v1:p"), neighbours)
        assertEquals(listOf("chat:room:v1:a"), CommunitiesViewModel.roomsFor(rooms, "affiliations", includePairs = true).map { it.communityId })
    }

    @Test
    fun aBare404IsTheNodeAnIdIsARefusal() {
        assertEquals(CommunityReadFailure.NotOnThisNode, CommunitiesViewModel.classify(NodeRefusal(null, null, 404)))
        assertEquals(CommunityReadFailure.NotOnThisNode, CommunitiesViewModel.classify(RouteNotOnThisHost("/v1/communities")))
        val refused = assertIs<CommunityReadFailure.Refused>(
            CommunitiesViewModel.classify(NodeRefusal("community.not_found", "no community", 404)),
        )
        assertEquals("community.not_found", refused.reasonId)
        assertIs<CommunityReadFailure.Refused>(CommunitiesViewModel.classify(NodeRefusal(null, "boom", 500)))
    }

    @Test
    fun notFoundOnOneRoomIsItsOwnState() {
        assertEquals(CommunityDetailRead.NotFound, CommunitiesViewModel.detailFailure(NodeRefusal("community.not_found", null, 404)))
        assertIs<CommunityDetailRead.Failed>(CommunitiesViewModel.detailFailure(NodeRefusal(null, null, 404)))
    }

    @Test
    fun aPairRoomIsWithTheContactWhosePairIdItIs() {
        val ann = Contact(keyId = "k-ann", chatCommunityId = "chat:pair:v1:p")
        val bo = Contact(keyId = "k-bo", chatCommunityId = "chat:pair:v1:q")
        assertEquals(ann, CommunitiesViewModel.pairContact(rooms[1], listOf(bo, ann)))
        assertNull(CommunitiesViewModel.pairContact(rooms[1], listOf(bo)), "no contact, no guess")
        assertNull(CommunitiesViewModel.pairContact(rooms[0], listOf(ann)), "a room of more than two has no one contact")
    }

    @Test
    fun aMemberAddedAfterFoundingIsLate_andUnknownIsNotLate() {
        val r = CommunityRoom(communityId = "c", foundedAt = "2026-09-01T00:00:00Z")
        assertEquals(false, CommunitiesViewModel.addedAfterFounding(CommunityRoomMember("a", joinedAt = "2026-09-01T00:00:00Z"), r))
        assertEquals(true, CommunitiesViewModel.addedAfterFounding(CommunityRoomMember("b", joinedAt = "2026-09-03T10:00:00+00:00"), r))
        assertNull(CommunitiesViewModel.addedAfterFounding(CommunityRoomMember("c", joinedAt = null), r))
        assertNull(CommunitiesViewModel.addedAfterFounding(CommunityRoomMember("d", joinedAt = "yesterday"), r))
    }

    @Test
    fun aQuorumsNIsTheFoundingRoster() {
        assertNull(CommunitiesViewModel.protocolFor("founder_only", null, 3), "the node's default is sent as nothing")
        assertEquals("majority", CommunitiesViewModel.protocolFor("majority", null, 3))
        assertEquals("quorum:2/3", CommunitiesViewModel.protocolFor("quorum", 2, 2))
        assertNull(CommunitiesViewModel.protocolFor("quorum", null, 2), "a quorum with no M is not a rule")
    }

    @Test
    fun aPasteIsReadAsWhatWasSent() {
        val env = CommunitiesViewModel.parseEnvelope("""{"change_envelope":{"op":"add","key_id":"k"},"valid":1}""")
        assertEquals("add", env?.get("op")?.toString()?.trim('"'))
        assertEquals("k", CommunitiesViewModel.parseEnvelope("""{"op":"remove","key_id":"k"}""")?.get("key_id")?.toString()?.trim('"'))
        assertNull(CommunitiesViewModel.parseEnvelope("not json"))
        assertNull(CommunitiesViewModel.parseEnvelope("{}"))
        val sig = CommunitiesViewModel.parseSignature("""{"community_id":"c","op":"add","signature":{"signer":"k-ann"}}""")
        assertEquals("""{"signer":"k-ann"}""", sig.toString())
        assertEquals("""{"signer":"k-bo"}""", CommunitiesViewModel.parseSignature("""{"signer":"k-bo"}""").toString())
        assertNull(CommunitiesViewModel.parseSignature("[1,2]"))
    }

    @Test
    fun theRuleSentenceIsChosenByFamily() {
        assertEquals("quorum", protocolFamily("quorum:3/5"))
        assertEquals("founder_only", protocolFamily("founder_only"))
        assertEquals("other", protocolFamily("weighted:x"))
    }

    @Test
    fun tagsUseTheRoomsOwnSuffix() {
        assertEquals("community_row_abc", CommunityTags.row("chat:room:v1:abc"))
        assertEquals("row_community_member_abc_k1", CommunityTags.member("chat:room:v1:abc", "k1"))
    }

    /**
     * `tier: community` is Neighbours and `tier: affiliations` is Communities
     * and Businesses (CohortScope's fold); who is in it is People, the rooms
     * are Chats, and the community itself is on that circle's Rules hub.
     */
    @Test
    fun eachTierLivesInItsOwnCircle() {
        val expected = mapOf(
            NavSurface.CommunityRoster to (Tab.PEOPLE to CohortScope.LOCAL_COMMUNITY),
            NavSurface.AffiliationsRoster to (Tab.PEOPLE to CohortScope.GLOBAL_COMMUNITIES),
            NavSurface.CommunityChats to (Tab.CHATS to CohortScope.LOCAL_COMMUNITY),
            NavSurface.AffiliationsChats to (Tab.CHATS to CohortScope.GLOBAL_COMMUNITIES),
        )
        for ((surface, where) in expected) {
            val p = CirclesNav.placementOf(surface) ?: error("${surface.id} is not placed")
            assertEquals(where.first, p.tab, surface.id)
            assertEquals(setOf(where.second), p.circles, surface.id)
            assertEquals(false, p.agentOnly, "${surface.id} calls the node only; a bare node must see it")
        }
        assertEquals(CohortScope.LOCAL_COMMUNITY.cegScope, "community")
        assertEquals(CohortScope.GLOBAL_COMMUNITIES.cegScope, "affiliations")
        // The governance half is on the hub that already stands for the circle, not a second Rules card.
        assertEquals(listOf(NavSurface.LayerLocalCommunity), CirclesNav.cards(CohortScope.LOCAL_COMMUNITY, Tab.RULES, hasAgent = false).take(1))
    }

    @Test
    fun theScreensLightTheirOwnSurfaces() {
        assertEquals(NavSurface.CommunityRoster, screenToSurface(Screen.CommunityRoster))
        assertEquals(NavSurface.AffiliationsRoster, screenToSurface(Screen.AffiliationsRoster))
        assertEquals(NavSurface.CommunityChats, screenToSurface(Screen.CommunityChats))
        assertEquals(NavSurface.AffiliationsChats, screenToSurface(Screen.AffiliationsChats))
    }
}
