package ai.ciris.mobile.shared.api

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * CIRISClient#26 — which local node runs in-process is not a compile-time fact.
 *
 * The field report: a desktop client launched with CIRIS_NODE_URL=:4343 sent all
 * twelve of its ordinary calls to :4343, then minted the owner's federation
 * identity on :4243 — a node the operator had never attached to. Keys created on
 * the wrong substrate while everything else was correct.
 *
 * The cause was `const val LOCAL_NODE_URL = "http://127.0.0.1:4243"` named
 * directly at ~30 federation call sites. Making it resolved rather than constant
 * fixes all of them at once, which is the point: a fix that depended on finding
 * every call site would have missed one.
 */
class LocalNodeResolutionTest {

    @AfterTest
    fun restore() {
        CIRISApiClient.setLocalNodeUrl(CIRISApiClient.DEFAULT_LOCAL_NODE_URL)
    }

    @Test
    fun the_app_can_declare_which_node_it_actually_drives() {
        CIRISApiClient.setLocalNodeUrl("http://127.0.0.1:4343")
        assertEquals("http://127.0.0.1:4343", CIRISApiClient.LOCAL_NODE_URL)
        assertNotEquals(
            CIRISApiClient.DEFAULT_LOCAL_NODE_URL, CIRISApiClient.LOCAL_NODE_URL,
            "the whole bug was that this could not change",
        )
    }

    @Test
    fun androids_node_is_8080_which_the_old_constant_could_never_express() {
        // Android's in-process ciris-server answers on :8080. Under a constant
        // pinned to :4243, every federation call site named a port nothing on
        // the device was listening on.
        CIRISApiClient.setLocalNodeUrl("http://localhost:8080")
        assertEquals("http://localhost:8080", CIRISApiClient.LOCAL_NODE_URL)
    }

    @Test
    fun a_trailing_slash_does_not_produce_a_double_slash_path() {
        // Call sites build "$localNodeUrl/v1/self/identity". A trailing slash
        // here yields //v1/... which some routers 404 and others accept, so it
        // is normalised once rather than at thirty call sites.
        CIRISApiClient.setLocalNodeUrl("http://127.0.0.1:4343/")
        assertEquals("http://127.0.0.1:4343", CIRISApiClient.LOCAL_NODE_URL)
    }

    @Test
    fun surrounding_whitespace_is_trimmed() {
        // An env var read from a shell profile can carry a newline.
        CIRISApiClient.setLocalNodeUrl("  http://127.0.0.1:4343\n")
        assertEquals("http://127.0.0.1:4343", CIRISApiClient.LOCAL_NODE_URL)
    }

    @Test
    fun a_blank_url_is_refused_rather_than_silently_accepted() {
        // An unset env var must not leave every federation call pointed at "".
        assertFailsWith<IllegalArgumentException> { CIRISApiClient.setLocalNodeUrl("") }
        assertFailsWith<IllegalArgumentException> { CIRISApiClient.setLocalNodeUrl("   ") }
        assertEquals(
            CIRISApiClient.DEFAULT_LOCAL_NODE_URL, CIRISApiClient.LOCAL_NODE_URL,
            "a refused value must not have been applied",
        )
    }

    @Test
    fun the_default_is_still_the_documented_one() {
        // Nothing that does not call setLocalNodeUrl changes behaviour.
        assertEquals("http://127.0.0.1:4243", CIRISApiClient.DEFAULT_LOCAL_NODE_URL)
    }
}
