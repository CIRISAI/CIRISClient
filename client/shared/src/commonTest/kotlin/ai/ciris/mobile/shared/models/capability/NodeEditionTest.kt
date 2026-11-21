package ai.ciris.mobile.shared.models.capability

import kotlin.test.Test
import kotlin.test.assertEquals

/** The mesh edition, from `/v1/federation/conformance` → `edition`, failing closed. */
class NodeEditionTest {

    @Test
    fun noEditionMemberIsTheEarlierMesh() {
        // 0.5.x: ceg_wire_version "1.0.0-rc2" and no `edition`.
        assertEquals(NodeEdition.EDITION_1, NodeEdition.fromConformance("""{"ceg_wire_version":"1.0.0-rc2","capabilities":[]}"""))
        assertEquals(NodeEdition.EDITION_1, NodeEdition.fromConformance("""{"data":{"ceg_wire_version":"1.0.0-rc2"}}"""))
    }

    @Test
    fun editionTwoIsV62InEitherEnvelope() {
        assertEquals(NodeEdition.EDITION_2, NodeEdition.fromConformance("""{"ceg_wire_version":"2.0.0","edition":2}"""))
        assertEquals(NodeEdition.EDITION_2, NodeEdition.fromConformance("""{"data":{"edition":2}}"""))
    }

    @Test
    fun anythingElseIsUnknownNeverAGuess() {
        assertEquals(NodeEdition.UNKNOWN, NodeEdition.fromConformance("""{"edition":"2"}"""))
        assertEquals(NodeEdition.UNKNOWN, NodeEdition.fromConformance("""{"edition":3}"""))
        assertEquals(NodeEdition.UNKNOWN, NodeEdition.fromConformance("""{"edition":null}"""))
        assertEquals(NodeEdition.UNKNOWN, NodeEdition.fromConformance("""{"edition":1}"""))
        assertEquals(NodeEdition.UNKNOWN, NodeEdition.fromConformance("not json"))
        assertEquals(NodeEdition.UNKNOWN, NodeEdition.fromConformance(""))
        assertEquals(NodeEdition.UNKNOWN, NodeEdition.fromConformance(null))
        assertEquals(NodeEdition.UNKNOWN, NodeEdition.fromConformance("[1,2]"))
    }

    @Test
    fun anUnreadDocumentIsUnknown() {
        assertEquals(NodeEdition.UNKNOWN, NodeCapabilities.UNREACHABLE.edition)
    }
}
