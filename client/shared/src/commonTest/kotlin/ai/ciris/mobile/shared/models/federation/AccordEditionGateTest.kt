package ai.ciris.mobile.shared.models.federation

import ai.ciris.mobile.shared.models.capability.NodeEdition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CSD-067 §4 on v62: the retired acts are offered only to an edition-1 node. */
class AccordEditionGateTest {

    private val all = listOf(
        "admit_node", "add_canonical", "bless_ci_workers", "remint_trust_root", "rescrub_trust_root",
        "cosign_paste", "found_accord", "confer_duty", "drill", "announce", "halt",
    )

    @Test
    fun editionOneKeepsEveryActAsBefore() {
        all.forEach { assertTrue(AccordEditionGate.offersNewAction(it, NodeEdition.EDITION_1), it) }
        assertTrue(AccordEditionGate.offersCanonicalDoors(NodeEdition.EDITION_1))
        assertTrue(AccordEditionGate.offersInAppCeremony(NodeEdition.EDITION_1))
        assertTrue(AccordEditionGate.readsEarlierMeshRoster(NodeEdition.EDITION_1))
        assertNull(AccordEditionGate.noteKey(NodeEdition.EDITION_1))
    }

    @Test
    fun v62AndAnUnknownNodeAreOfferedOnlyTheActsBothEditionsHave() {
        for (e in listOf(NodeEdition.EDITION_2, NodeEdition.UNKNOWN)) {
            assertEquals(
                listOf("drill", "announce", "halt"),
                all.filter { AccordEditionGate.offersNewAction(it, e) },
                "$e",
            )
            assertFalse(AccordEditionGate.offersCanonicalDoors(e))
            assertFalse(AccordEditionGate.offersInAppCeremony(e))
            assertFalse(AccordEditionGate.readsEarlierMeshRoster(e))
        }
        assertEquals("mobile.accord_edition2_offline_ceremony", AccordEditionGate.noteKey(NodeEdition.EDITION_2))
        assertEquals("mobile.accord_edition_unknown", AccordEditionGate.noteKey(NodeEdition.UNKNOWN))
    }
}
