package ai.ciris.mobile.shared.ceremony

/**
 * WHAT THE CEREMONY TOOL IS SAYING (CSD-069): its prompts, its touch line and its
 * refusals, read from the text it prints. The texts are the tool's own
 * (genesis-ceremony.rs and signers.rs at 0dabc27e); CeremonyOutputTest pins them.
 */
object CeremonyOutput {
    /** `>>> Touch the YubiKey now (2 of 3). <<<` (signers.rs). */
    private val TOUCH = Regex(""">>> Touch the YubiKey now \((\d+) of (\d+)\)\. <<<""")
    private val TOUCH_ANY = Regex(""">>> Touch the YubiKey now \(signing\)\. <<<""")
    /** signers.rs: `rpassword::prompt_password(format!("YubiKey PIV PIN (slot {slot}): "))`, exactly. */
    private val PIN_PROMPT = Regex("""^YubiKey PIV PIN \(slot [0-9a-fA-F]{2}\): ?$""")
    private val ANSI = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]""")

    /** Which prompt the tool is waiting on, read from its last unfinished line. */
    enum class Prompt {
        /** `Type yes to sign (do NOT touch the YubiKey yet): `. */
        CONFIRM,
        /** `Remove holder A1's YubiKey, insert the BACKUP YubiKey, then press Enter: `. */
        SWAP_TOKEN,
        /** `YubiKey PIV PIN (slot 9c): `, read by rpassword from the tool's own terminal. */
        PIN,
    }

    data class Touch(val n: Int, val of: Int)

    /** Normalise a terminal chunk: CRLF to LF, no escape sequences. */
    fun clean(chunk: String): String = ANSI.replace(chunk, "").replace("\r\n", "\n").replace('\r', '\n')

    /**
     * The prompt the tool is waiting on, or null. A prompt is printed without a newline
     * and the tool then blocks, so only the text after the last newline counts: once the
     * answer's echo or the next line arrives, it is no longer pending.
     */
    fun pendingPrompt(transcript: String): Prompt? {
        val tail = transcript.substringAfterLast('\n')
        return when {
            tail.startsWith("Type yes to sign") && tail.trimEnd().endsWith(":") -> Prompt.CONFIRM
            tail.startsWith("Remove holder ") && tail.contains("insert the BACKUP YubiKey") && tail.trimEnd().endsWith(":") -> Prompt.SWAP_TOKEN
            PIN_PROMPT.matches(tail) -> Prompt.PIN
            else -> null
        }
    }

    /** The latest touch the tool asked for, or null when it has asked for none. */
    fun lastTouch(transcript: String): Touch? {
        val m = TOUCH.findAll(transcript).lastOrNull()
        if (m != null) return Touch(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        return if (TOUCH_ANY.containsMatchIn(transcript)) Touch(1, 1) else null
    }

    /** Whether the person's last touch was read as a one-time password (the tool says so and asks again). */
    fun otpTyped(transcript: String): Boolean =
        transcript.contains("That was a YubiKey one-time password")

    fun finalGatePassed(transcript: String): Boolean = transcript.contains("FINAL GATE PASSED")

    /** What a refusal was, when its text is one this screen explains; [OTHER] shows the tool's own text only. */
    enum class Refusal(val key: String) {
        SERIAL_COLLISION("mobile.genesis_refusal_serial_collision"),
        RECOVERY_KEY("mobile.genesis_refusal_recovery_key"),
        NO_TCP_HINT("mobile.genesis_refusal_no_tcp_hint"),
        HALT_MISSING("mobile.genesis_refusal_halt_missing"),
        WRONG_TOKEN("mobile.genesis_refusal_wrong_token"),
        NOT_CONFIRMED("mobile.genesis_refusal_not_confirmed"),
        WOULD_OVERWRITE("mobile.genesis_refusal_would_overwrite"),
        OTHER("mobile.genesis_refusal_other"),
    }

    /** The tool's error text: what follows `Error: ` to the end (anyhow's report, its causes included). */
    fun errorText(transcript: String): String? {
        val i = transcript.lastIndexOf("\nError: ").let { if (it >= 0) it + 1 else if (transcript.startsWith("Error: ")) 0 else -1 }
        return if (i < 0) null else transcript.substring(i).trim()
    }

    fun refusalOf(error: String): Refusal = when {
        "is the holder token's: insert the BACKUP YubiKey" in error ||
            "the backup token holds the holder's own key" in error -> Refusal.SERIAL_COLLISION
        "ceremony_recovery_key_is_signing_key" in error ||
            Regex("""signing or recovery key is \S+'s signing key""").containsMatchIn(error) -> Refusal.RECOVERY_KEY
        "at least one tcp dial hint" in error -> Refusal.NO_TCP_HINT
        "halt coverage incomplete" in error || "two sealed halts for holder" in error -> Refusal.HALT_MISSING
        Regex("""is not the prepared .*; refusing""").containsMatchIn(error) ||
            Regex("""are not holder \S+ prepared key""").containsMatchIn(error) -> Refusal.WRONG_TOKEN
        "not confirmed; nothing was signed" in error -> Refusal.NOT_CONFIRMED
        "refusing to overwrite" in error -> Refusal.WOULD_OVERWRITE
        else -> Refusal.OTHER
    }
}

/**
 * THE PIN NEVER APPEARS IN THE TRANSCRIPT. The tool's terminal turns echo off before it
 * reads the PIN, and the screen sends the PIN only once the tool is waiting on it, so
 * nothing should come back. This is the belt to that brace: for a short while after the
 * PIN is written, any output that carries it is shown with the PIN masked. The guard
 * holds its own copy, wipes it when the window ends, and never makes it a String.
 */
class PinEchoGuard(private val windowMs: Long = 3_000) {
    private var pin: CharArray? = null
    private var until: Long = 0

    /** Arm with a COPY of the PIN just written, at [nowMs]. */
    fun arm(pinCopy: CharArray, nowMs: Long) {
        wipe()
        pin = pinCopy
        until = nowMs + windowMs
    }

    /** [chunk] with every occurrence of the armed PIN masked; disarms once the window has passed. */
    fun scrub(chunk: String, nowMs: Long): String {
        val p = pin ?: return chunk
        val out = if (p.isEmpty()) chunk else mask(chunk, p)
        if (nowMs >= until) wipe()
        return out
    }

    fun wipe() {
        pin?.fill('\u0000')
        pin = null
    }

    private fun mask(s: String, p: CharArray): String {
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            var match = i + p.size <= s.length
            var k = 0
            while (match && k < p.size) {
                if (s[i + k] != p[k]) match = false
                k++
            }
            if (match) {
                b.append("•".repeat(p.size)); i += p.size
            } else {
                b.append(s[i]); i++
            }
        }
        return b.toString()
    }
}
