package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.models.federation.CommunityRoom
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CSD-102 §3.1 / CIRISServer#688: an unreadable moderator chain must not read
 * as "no moderator is appointed", and a list the node never sent must not
 * either. Four facts, four renderings.
 */
class ModeratorsShownTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun room(body: String) = json.decodeFromString(CommunityRoom.serializer(), body)

    @Test
    fun anUnreadableChainIsNotNone() {
        val r = room("""{"community_id":"c","moderators":[],"moderators_readable":false}""")
        assertEquals(ModeratorsShown.Unreadable, moderatorsShown(r))
    }

    @Test
    fun aListTheNodeDidNotSendIsNotNone() {
        val r = room("""{"community_id":"c"}""")
        assertEquals(ModeratorsShown.NotSent, moderatorsShown(r))
    }

    @Test
    fun aReadEmptyListIsNone() {
        assertEquals(ModeratorsShown.None, moderatorsShown(room("""{"community_id":"c","moderators":[]}""")))
        assertEquals(ModeratorsShown.None, moderatorsShown(room("""{"community_id":"c","moderators":[],"moderators_readable":true}""")))
    }

    @Test
    fun holdersAreListed() {
        val r = room("""{"community_id":"c","moderators":["wa-mem-9b02"]}""")
        assertEquals(ModeratorsShown.Holders(listOf("wa-mem-9b02")), moderatorsShown(r))
    }
}
