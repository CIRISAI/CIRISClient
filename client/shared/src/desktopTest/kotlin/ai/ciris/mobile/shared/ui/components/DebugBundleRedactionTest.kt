package ai.ciris.mobile.shared.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bundle is built to be handed to someone else. Redaction is therefore not
 * about what we log today — nothing logs a credential today — but about what
 * escapes when somebody later logs one while chasing a bug.
 *
 * Both directions matter equally. A redactor that misses a JWT leaks; a
 * redactor that eats ordinary log lines destroys the only artifact a stranded
 * user can send us. The second half of these tests is the half that keeps the
 * patterns honest.
 */
class DebugBundleRedactionTest {

    private fun redact(s: String) = DebugBundle.redactSecrets(s)

    @Test
    fun `removes jwts`() {
        val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVP"
        val out = redact("[INFO] auth: exchanged $jwt ok")
        assertFalse(out.contains("eyJhbGciOi"), "JWT survived redaction")
        assertTrue(out.contains("[INFO] auth:"), "redaction ate the surrounding line")
    }

    @Test
    fun `removes bearer headers including service tokens`() {
        assertFalse(
            redact("Authorization: Bearer abcdef0123456789abcdef").contains("abcdef0123456789"),
        )
        assertFalse(
            redact("header Bearer service:9f8e7d6c5b4a39281706").contains("9f8e7d6c5b4a"),
        )
    }

    @Test
    fun `removes secret-shaped assignments`() {
        for (line in listOf(
            "api_key=sk-live-abcdef123456",
            "access_token: ghp_AAAABBBBCCCCDDDDEEEE",
            "password = hunter2hunter2",
            """{"client_secret":"s3cr3t-value-here"}""",
        )) {
            val out = redact(line)
            assertTrue(out.contains("<redacted>"), "not redacted: $line -> $out")
        }
        assertFalse(redact("password = hunter2hunter2").contains("hunter2hunter2"))
    }

    @Test
    fun `leaves ordinary diagnostics untouched`() {
        // Every one of these is a line we NEED in a bug report.
        for (line in listOf(
            // The exact lines the Esu report turned on: an auth failure must
            // survive intact, or the bundle loses the one fact worth sending.
            "[ERROR] CIRISApp: token exchange failed: HTTP 401",
            "[ERROR] auth: token exchange failed, no refresh token present",
            "token: expired",
            "[INFO] startup: 22/22 services healthy in 4.2s",
            "client version: 0.5.190",
            "app version: 2.9.41",
            "device: Linux 7.0.0-30-generic amd64",
            "screen: login",
            "[WARN] node: attestation deadline exceeded (20s budget)",
            "GET /v1/system/health -> 200 in 34ms",
        )) {
            assertEquals(line, redact(line), "redactor damaged an ordinary log line")
        }
    }

    @Test
    fun `render passes its whole output through redaction`() {
        val out = DebugBundle.render(
            mapOf("error (full)" to "refresh_token=rt_abcdef0123456789 rejected"),
        )
        assertFalse(out.contains("rt_abcdef0123456789"), "extra map bypassed redaction")
        assertTrue(out.contains("CIRIS debug bundle"))
    }

    @Test
    fun a_quoted_secret_containing_spaces_is_removed_whole() {
        // The value group used to stop at the first space, so a four-word
        // passphrase lost one word and kept three (Codex, PR #18).
        val out = DebugBundle.redactSecrets("""password="correct horse battery staple"""" + "\"")
        assertFalse(out.contains("horse"), "the rest of the passphrase survived: $out")
        assertFalse(out.contains("staple"), "the rest of the passphrase survived: $out")
        assertTrue(out.contains("<redacted>"), out)
    }

    @Test
    fun a_quoted_state_is_still_not_a_credential() {
        val out = DebugBundle.redactSecrets("""token="expired"""" + "\"")
        assertTrue(out.contains("expired"), "a state was redacted to nothing: $out")
    }

    @Test
    fun a_double_quoted_secret_may_contain_an_apostrophe() {
        // A single arm excluding BOTH quote types never matched this, so it
        // fell through to the unquoted arm and leaked the rest (Codex, PR #18).
        val out = DebugBundle.redactSecrets("password=\"correct horse's battery staple\"")
        assertFalse(out.contains("battery"), "the credential survived: $out")
        assertFalse(out.contains("staple"), "the credential survived: $out")
        assertTrue(out.contains("<redacted>"), out)
    }

    @Test
    fun a_single_quoted_secret_may_contain_a_double_quote() {
        val out = DebugBundle.redactSecrets("password='he said \"open sesame\" twice'")
        assertFalse(out.contains("sesame"), "the credential survived: $out")
        assertTrue(out.contains("<redacted>"), out)
    }

    @Test
    fun compound_credential_names_are_the_ones_this_codebase_uses() {
        // llm_api_key appears 7 times in the models; \b cannot fire after an
        // underscore, so the pattern was blind to exactly these spellings
        // (Codex, PR #18).
        for (line in listOf(
            "llm_api_key=sk-abcdef123456",
            "owner_password=hunter2hunter2",
            "system_admin_password=hunter2hunter2",
            "backup_llm_api_key=sk-abcdef123456",
        )) {
            val out = DebugBundle.redactSecrets(line)
            assertTrue(out.contains("<redacted>"), "not redacted: $out")
            assertFalse(out.contains("sk-abcdef123456"), "credential survived: $out")
            assertFalse(out.contains("hunter2hunter2"), "credential survived: $out")
        }
    }

    @Test
    fun a_status_flag_is_not_a_credential() {
        // `llm_api_key_set=true` is a boolean about configuration. Redacting it
        // costs the reader the fact the line carried.
        val out = DebugBundle.redactSecrets("llm_api_key_set=true")
        assertTrue(out.contains("true"), "a status flag was redacted: $out")
    }

    @Test
    fun an_escaped_delimiter_does_not_end_the_value() {
        val out = DebugBundle.redactSecrets(
            "{\"password\":\"correct \\\"horse\\\" battery staple\"}"
        )
        assertFalse(out.contains("battery"), "the credential survived: $out")
        assertFalse(out.contains("staple"), "the credential survived: $out")
    }

    @Test
    fun a_short_credential_is_still_a_credential() {
        // The value quantifier was {6,}, so anything shorter was exported whole
        // (Codex, PR #18). The NAME is the shape check; length was never it.
        for (line in listOf("client_secret=\"abc\"", "password=12345", "token=x1")) {
            val out = DebugBundle.redactSecrets(line)
            assertTrue(out.contains("<redacted>"), "not redacted: $out")
        }
    }

    @Test
    fun a_short_status_is_still_a_status() {
        // Relaxing the quantifier made the state list load-bearing for words
        // that are now long enough to match.
        for (line in listOf("token: ok", "llm_api_key=unset", "password: none", "secret=null")) {
            val out = DebugBundle.redactSecrets(line)
            assertFalse(out.contains("<redacted>"), "a status was redacted: $out")
        }
    }

    @Test
    fun the_patterns_construct_without_inline_flags() {
        // `(?i)` is invalid in ECMAScript, so on wasmJs building these threw at
        // object init — and `environment()` touches the object, so "Open a
        // GitHub issue" on a failure panel crashed before it could open
        // (Codex, PR #18). Constructing and using them is the assertion.
        assertTrue(DebugBundle.redactSecrets("API_KEY=sk-abcdef").contains("<redacted>"))
        assertTrue(DebugBundle.redactSecrets("Bearer AbCdEf0123456789").contains("<redacted>"))
    }
}