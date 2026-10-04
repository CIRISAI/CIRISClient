package ai.ciris.mobile.shared.platform

import kotlin.concurrent.Volatile

/**
 * **Fields whose text automation may apply but must never hold or echo** — a
 * YubiKey PIN, above all (Codex on CIRISClient#154).
 *
 * The automation surface records what `/input` typed so it can be read back
 * (CIRISClient#31): `setInputValue`, the `text` of the acknowledgement, and
 * `inputValue` / `text` on `/tree` and `/element`. For a PIN that is the PIN,
 * readable by anything that can reach the automation port. A tag marked here
 * is still a sink — `/input` applies to it and waits for the apply — but the
 * shared handler, the desktop server and the field driver all skip storing
 * and echoing it, and `/tree` shows the field present with no text.
 *
 * Common, not per platform: one registry that every server reads, so no
 * platform can be the one that forgot. Copy-on-write so the automation
 * server's thread reads a consistent set.
 */
object SensitiveInputs {
    @Volatile
    private var tags: Set<String> = emptySet()

    fun mark(tag: String) {
        tags = tags + tag
    }

    fun unmark(tag: String) {
        tags = tags - tag
    }

    fun isSensitive(tag: String): Boolean = tag in tags
}
