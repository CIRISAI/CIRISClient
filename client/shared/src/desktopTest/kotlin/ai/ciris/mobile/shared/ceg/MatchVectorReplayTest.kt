package ai.ciris.mobile.shared.ceg

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * THE REFERENCE MATCHER'S VECTORS, REPLAYED AGAINST [Dim.forWire]
 * (CIRISClient#150; `client/ceg/namespace_match_vectors.json`, vendored from
 * CIRISConstitution v1.0-rc6 with the registry it was generated against).
 *
 * What this covers: every vector the reference ADMITS (`refusal == null`)
 * resolves, through the client's renderer lookup, to exactly the family the
 * reference names. A receipt row the substrate accepts is drawn as the right
 * family.
 *
 * What it does NOT cover: the refusal vectors. `forWire` is a resolver for
 * rendering — it finds the family a wire string belongs to and is deliberately
 * lenient about version tails and closed leaves. Refusing malformed forms is
 * the validator's job, and porting the reference matcher into
 * `packaging/check_csd_v3.py` is CIRISClient#114.
 */
class MatchVectorReplayTest {

    private fun vectors(): JsonObject {
        val roots = listOf("../ceg", "ceg", "client/ceg")
        val f = roots.map { File(it, "namespace_match_vectors.json") }.firstOrNull { it.exists() }
            ?: error("namespace_match_vectors.json not found from ${File(".").absolutePath}")
        return Json.parseToJsonElement(f.readText()).jsonObject
    }

    @Test
    fun theVectorsWereGeneratedAgainstThePinnedRegistry() {
        val meta = vectors()["_meta"]!!.jsonObject
        assertEquals(REGISTRY_SHA256, meta["registry_sha256"]!!.jsonPrimitive.content,
            "the vectors and the registry must be vendored from the same CC commit")
        assertEquals(REGISTRY_CC_VERSION, meta["cc_version"]!!.jsonPrimitive.content)
    }

    @Test
    fun everyAdmittedVectorResolvesToTheReferenceFamily() {
        val admitted = vectors()["vectors"]!!.jsonArray.map { it.jsonObject }
            .filter { it["refusal"] == null || it["refusal"] is JsonNull }
        // A parser that finds nothing where the construct plainly exists fails loudly.
        assertTrue(admitted.size >= 100, "only ${admitted.size} admitted vectors parsed — shape changed?")
        val wrong = admitted.mapNotNull { v ->
            val dim = v["dimension"]!!.jsonPrimitive.content
            val want = v["family"]!!.jsonPrimitive.content
            val got = Dim.forWire(dim)?.prefix
            if (got == want) null else "$dim → $got (reference: $want)"
        }
        assertTrue(wrong.isEmpty(), "${wrong.size} of ${admitted.size} admitted vectors resolve wrongly:\n" +
            wrong.joinToString("\n"))
    }
}
