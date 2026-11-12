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
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The decisions the communities cards make without a socket (CSD-102, CSD-103):
 * which rooms a card of a tier shows, how a failure is read, who a pair room is
 * with, who joined late, and what a paste is.
 */
class CommunitiesLogicTest {

    /**
     * CIRISServer 0.5.246: a room holds two people for now, counting open
     * invitations. Invite is hidden only where the node has said so, on the room
     * or by its refusal; an older node's rooms keep it.
     */
    /**
     * The answers a ciris-server 0.5.246 node gave (release/0.5.246 `2bfb48a7`, two
     * nodes, 2026-11-12), read with this client's own models. A founder alone in a
     * room that holds two can invite; with one invitation open the room is full,
     * and the node refused the next one with 409 `community.two_people_only`
     * `{max_people: 2, members: 1, open_invitations: 1}`.
     */
    @Test
    fun theNodesOwnAnswersMakeARoomFullAtOneMemberAndOneOpenInvitation() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val room = json.decodeFromString<CommunityRoom>(
            """{"cohort_scope": "community", "community_id": "chat:room:v1:3ebf42a12c1743c68a6df2aeb8e47962", "consensus_protocol": "majority", "founded_at": "2026-11-12T12:28:32.158+00:00", "kind": "room", "max_people": 2, "member_count": 1, "members": [{"joined_at": "2026-11-12T12:28:32.158+00:00", "key_id": "ciris-mod-a-11f17ac6-user-ixydwigu22", "role": "founder"}], "moderators": ["ciris-mod-a-11f17ac6-user-ixydwigu22"], "my_role": "founder", "name": "two people", "revocations": 0, "roles": {"founder": ["ciris-mod-a-11f17ac6-user-ixydwigu22"]}, "tier": "community", "widenings": 0}""",
        )
        assertEquals(2, room.maxPeople, "the node says the limit on the room itself")
        assertFalse(CommunitiesViewModel.roomIsFull(room, openInvitations = 0, learnedLimit = null), "a founder alone may invite one person")
        val invites = json.decodeFromString<ai.ciris.mobile.shared.models.federation.GroupInviteList>(
            """{"community_id": "chat:room:v1:3ebf42a12c1743c68a6df2aeb8e47962", "invites": [{"expires_at": "2026-11-26T12:28:32.181+00:00", "invitee_key_id": "ciris-mod-b-9cb6cbe1-user-fhsce5njaz", "proposal_id": "f592ec06-8fc4-464e-8b11-c1eae86e0d2f", "proposed_at": "2026-11-12T12:28:32.182+00:00", "proposer_key_id": "ciris-mod-a-11f17ac6-user-ixydwigu22", "reply_id": null, "role": null, "state": "pending"}], "seated_now": []}""",
        )
        val open = invites.invites.count {
            it.state == ai.ciris.mobile.shared.models.federation.InviteState.PENDING ||
                it.state == ai.ciris.mobile.shared.models.federation.InviteState.ACCEPTED
        }
        assertEquals(1, open)
        assertTrue(CommunitiesViewModel.roomIsFull(room, open, learnedLimit = null), "one member plus one open invitation: where the node refused the next invite")
        // A pair room arrives with the same limit and both people in it.
        val pair = json.decodeFromString<CommunityRoom>("""{"community_id": "chat:pair:v1:ef3e5d77", "kind": "pair", "max_people": 2, "member_count": 2}""")
        assertTrue(CommunitiesViewModel.roomIsFull(pair, 0, null))
    }

    @Test
    fun aRoomIsFullOnlyWhereTheNodeSaysItsLimit() {
        fun members(n: Int) = (1..n).map { ai.ciris.mobile.shared.models.federation.CommunityRoomMember(keyId = "k$it") }
        val one = CommunityRoom(communityId = "r", members = members(1), memberCount = 1)
        val two = CommunityRoom(communityId = "r", members = members(2), memberCount = 2)
        // No limit said, on the room or by a refusal: never "full", whatever its size.
        assertFalse(CommunitiesViewModel.roomIsFull(two, openInvitations = 3, learnedLimit = null))
        // The room carries its limit.
        assertFalse(CommunitiesViewModel.roomIsFull(one.copy(maxPeople = 2), 0, null), "one person, nobody invited: room for one more")
        assertTrue(CommunitiesViewModel.roomIsFull(one.copy(maxPeople = 2), 1, null), "one member plus an open invitation is two")
        assertTrue(CommunitiesViewModel.roomIsFull(two.copy(maxPeople = 2), 0, null))
        // The node said so by refusing once.
        assertFalse(CommunitiesViewModel.roomIsFull(one, 0, CommunitiesViewModel.TWO_PEOPLE))
        assertTrue(CommunitiesViewModel.roomIsFull(one, 1, CommunitiesViewModel.TWO_PEOPLE))
        assertTrue(CommunitiesViewModel.roomIsFull(two, 0, CommunitiesViewModel.TWO_PEOPLE))
        // The room's own word wins over what was learned.
        assertFalse(CommunitiesViewModel.roomIsFull(two.copy(maxPeople = 5), 0, CommunitiesViewModel.TWO_PEOPLE))
        // A listing that carries only a count, not the roster.
        assertTrue(CommunitiesViewModel.roomIsFull(CommunityRoom(communityId = "r", memberCount = 2, maxPeople = 2), 0, null))
    }

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
            NavSurface.CommunityRoster to (Tab.PEOPLE to CohortScope.GLOBAL_COMMUNITIES),
            NavSurface.AffiliationsRoster to (Tab.PEOPLE to CohortScope.GLOBAL_COMMUNITIES),
            NavSurface.CommunityChats to (Tab.CHATS to CohortScope.GLOBAL_COMMUNITIES),
            NavSurface.AffiliationsChats to (Tab.CHATS to CohortScope.GLOBAL_COMMUNITIES),
        )
        for ((surface, where) in expected) {
            val p = CirclesNav.placementOf(surface) ?: error("${surface.id} is not placed")
            assertEquals(where.first, p.tab, surface.id)
            assertEquals(setOf(where.second), p.circles, surface.id)
            assertEquals(false, p.agentOnly, "${surface.id} calls the node only; a bare node must see it")
        }
        assertEquals(CohortScope.GLOBAL_COMMUNITIES.cegScope, "community")
        assertEquals(null, CohortScope.LOCAL_COMMUNITY.cegScope, "no wire token is geographic yet, so no room lands in Local community")
        // The governance half is on the hub that already stands for the circle, not a second Rules card.
        assertEquals(
            listOf(NavSurface.LayerGlobalCommunities, NavSurface.LayerAffiliations),
            CirclesNav.cards(CohortScope.GLOBAL_COMMUNITIES, Tab.RULES, hasAgent = false).take(2),
        )
        assertEquals(listOf(NavSurface.LayerLocalCommunity), CirclesNav.cards(CohortScope.LOCAL_COMMUNITY, Tab.RULES, hasAgent = false).take(1))
        assertEquals(Tab.CHATS, CirclesNav.landingTab(CohortScope.GLOBAL_COMMUNITIES), "the circle opens on its lists")
    }

    @Test
    fun theScreensLightTheirOwnSurfaces() {
        assertEquals(NavSurface.CommunityRoster, screenToSurface(Screen.CommunityRoster))
        assertEquals(NavSurface.AffiliationsRoster, screenToSurface(Screen.AffiliationsRoster))
        assertEquals(NavSurface.CommunityChats, screenToSurface(Screen.CommunityChats))
        assertEquals(NavSurface.AffiliationsChats, screenToSurface(Screen.AffiliationsChats))
    }

    // ── Local rooms ─────────────────────────────────────────────────────────

    @Test
    fun aRoomIsOnOneListOnly() {
        val rooms = listOf(
            room("plain", "community"),
            room("marked", "community"),
            room("served", "community").copy(cohortSubkind = CommunityRoom.SUBKIND_GEOGRAPHIC),
            room("work", "affiliations"),
        )
        val marks = setOf("marked")
        fun ids(tier: String, local: Boolean) =
            CommunitiesViewModel.roomsFor(rooms, tier, includePairs = true, local = local, localMarks = marks)
                .map { it.communityId }
        assertEquals(listOf("marked", "served"), ids("community", local = true))
        assertEquals(listOf("plain"), ids("community", local = false))
        assertEquals(listOf("work"), ids("affiliations", local = false))
    }

    @Test
    fun aMarkSurvivesARestart() = kotlinx.coroutines.test.runTest {
        var stored: String? = null
        val before = LocalRoomMarks(read = { stored }, write = { stored = it })
        before.add("a")
        before.add("b")
        val after = LocalRoomMarks(read = { stored }, write = { stored = it })
        after.load()
        assertEquals(setOf("a", "b"), after.ids.value)
    }

    @Test
    fun aRoomReadsItsSubkindAndAnOlderNodeSendsNone() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val served = json.decodeFromString(
            CommunityRoom.serializer(),
            """{"community_id":"x","tier":"community","cohort_subkind":"geographic"}""",
        )
        assertEquals("geographic", served.cohortSubkind)
        assertNull(json.decodeFromString(CommunityRoom.serializer(), """{"community_id":"x"}""").cohortSubkind)
    }

    @Test
    fun localCommunityOpensOnItsRoomsAndHasPeople() {
        assertEquals(Tab.CHATS, CirclesNav.landingTab(CohortScope.LOCAL_COMMUNITY))
        assertEquals(listOf(NavSurface.LocalChats), CirclesNav.cards(CohortScope.LOCAL_COMMUNITY, Tab.CHATS, hasAgent = false))
        assertEquals(NavSurface.LocalRoster, CirclesNav.cards(CohortScope.LOCAL_COMMUNITY, Tab.PEOPLE, hasAgent = false).first { it == NavSurface.LocalRoster })
    }
}
