package ai.ciris.mobile.shared.models.federation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

/**
 * `durable_queue_depth` changed shape at CIRISServer 0.5.223 (CIRISEdge#845):
 * a per-class map became one resident-row count, or `null` when the queue could
 * not be counted. A `Map<String, Long>` field failed the whole metrics decode on
 * the number. Every shape must decode, and `null` must stay "not read", not 0.
 */
class FederationMetricsDecodeTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun decode(raw: String) = json.decodeFromString(FederationMetricsResponse.serializer(), raw)

    @Test
    fun resident_count_number() {
        val m = decode("""{"durable_queue_depth":7,"durable_enqueued_total":{"best_effort":3,"durable":40}}""")
        assertEquals(7L, m.getQueueDepth())
        assertEquals(40L, m.durableEnqueuedTotal?.get("durable"))
        assertEquals(mapOf("resident" to 7.0), m.families()[FederationMetricsResponse.FAMILY_DURABLE_QUEUE_DEPTH])
    }

    @Test
    fun uncountable_queue_is_not_zero() {
        assertNull(decode("""{"durable_queue_depth":null}""").getQueueDepth())
        assertNull(decode("""{}""").getQueueDepth())
    }

    @Test
    fun pre_223_map_still_sums() {
        assertEquals(5L, decode("""{"durable_queue_depth":{"durable":2,"receipt":3}}""").getQueueDepth())
    }
}
