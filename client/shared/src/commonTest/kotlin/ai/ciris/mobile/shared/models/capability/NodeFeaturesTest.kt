package ai.ciris.mobile.shared.models.capability

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `features` (served routes) is read apart from `capabilities` (conferred authority), CIRISServer 0.5.246. */
class NodeFeaturesTest {
    private fun read(doc: String) =
        CapabilityWire.parse(doc, CapabilityWire.FIELD_CONFERRED).copy(features = CapabilityWire.parse(doc, CapabilityWire.FIELD_FEATURES).declared)

    @Test fun aServedCallRouteShowsTheCallAction() {
        assertTrue(read("""{"capabilities":[],"features":["chat:call"]}""").hasFeature(Capability.CHAT_CALL))
    }

    @Test fun conferredAuthorityIsNotARoute() {
        assertFalse(read("""{"capabilities":["chat:call"]}""").hasFeature(Capability.CHAT_CALL))
    }

    @Test fun aNodeWithNoFeatureListServesNoCall() {
        assertFalse(read("""{"capabilities":[]}""").hasFeature(Capability.CHAT_CALL))
        assertFalse(NodeCapabilities.UNREACHABLE.hasFeature(Capability.CHAT_CALL))
        assertFalse(NodeCapabilities.UNDECLARED.hasFeature(Capability.CHAT_CALL))
    }
}
