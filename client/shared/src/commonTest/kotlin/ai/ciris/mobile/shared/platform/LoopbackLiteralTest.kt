package ai.ciris.mobile.shared.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `localhost` is a name a device may fail to resolve; 127.0.0.1 is not (LoopbackLiteral.kt). */
class LoopbackLiteralTest {
    @Test fun localhost_becomes_the_loopback_address_and_nothing_else_changes() {
        assertEquals("http://127.0.0.1:8080/v1/system/health", loopbackLiteral("http://localhost:8080/v1/system/health"))
        assertEquals("http://127.0.0.1:4243", loopbackLiteral("http://LOCALHOST:4243"))
        assertEquals("http://127.0.0.1/x?y=1#z", loopbackLiteral("http://localhost/x?y=1#z"))
        assertEquals("http://127.0.0.1", loopbackLiteral("http://localhost"))
    }

    @Test fun other_hosts_and_lookalikes_are_untouched() {
        for (u in listOf("http://127.0.0.1:8080", "https://agents.ciris.ai/v1", "http://localhost.example.com:8080/",
                         "http://mylocalhost:8080", "not a url", "")) {
            assertEquals(u, loopbackLiteral(u), u)
        }
    }

    @Test fun only_the_name_localhost_is_a_loopback_name() {
        assertTrue(isLoopbackName("localhost")); assertTrue(isLoopbackName("LocalHost"))
        assertFalse(isLoopbackName("127.0.0.1")); assertFalse(isLoopbackName("localhost.example.com"))
    }

    @Test fun a_tail_starts_at_a_line_and_says_it_was_cut() {
        val text = (1..100).joinToString("\n") { "line $it" }
        val tail = tailText(text.encodeToByteArray(), 40)
        assertTrue(tail.startsWith("… (earlier lines cut)\n"), tail)
        assertTrue(tail.trimEnd().endsWith("line 100"), tail)
        assertFalse(tail.lines().drop(1).first().startsWith("ne "), "the first kept line must be whole: $tail")
        assertEquals("short\n", tailText("short\n".encodeToByteArray(), 40))
        assertTrue(tailText("half line\nwhole\n".encodeToByteArray(), 100, earlierCut = true).endsWith("whole\n"))
    }
}
