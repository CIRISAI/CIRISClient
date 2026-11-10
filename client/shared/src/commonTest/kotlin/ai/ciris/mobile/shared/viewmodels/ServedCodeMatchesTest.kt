package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.platform.util.DecodedNodeCode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The node screen's identity pin, with the v3 commitment (CC 2.6.8, CIRISEdge#964). */
class ServedCodeMatchesTest {
    private val v1 = DecodedNodeCode(keyId = "lab-node-wyxim75c6m", pubkeyEd25519Base64 = "AAAA")
    private val v3 = v1.copy(mlDsa65PubkeySha256 = "2ec25edcc223dd788787659cd835a224d6362a53c57d7f3f62c81773cd0d97c1")

    @Test fun theSameNodeMatches() {
        assertTrue(servedCodeMatches(scanned = v1, served = v1))
        assertTrue(servedCodeMatches(scanned = v3, served = v3))
    }

    @Test fun aV1CodeDoesNotRequireACommitment() {
        assertTrue(servedCodeMatches(scanned = v1, served = v3))
    }

    @Test fun aDifferentCommitmentIsNotTheSameNode() {
        assertFalse(servedCodeMatches(scanned = v3, served = v3.copy(mlDsa65PubkeySha256 = "0".repeat(64))))
    }

    @Test fun aServedCodeWithoutTheCommitmentIsNotTheSameNode() {
        assertFalse(servedCodeMatches(scanned = v3, served = v1))
    }

    @Test fun aDifferentEd25519KeyIsNotTheSameNode() {
        assertFalse(servedCodeMatches(scanned = v1, served = v1.copy(pubkeyEd25519Base64 = "BBBB")))
    }
}
