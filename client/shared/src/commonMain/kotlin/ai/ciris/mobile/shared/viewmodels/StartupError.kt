package ai.ciris.mobile.shared.viewmodels

/**
 * **Why startup failed, as a value the screen localizes when it draws.**
 *
 * The view model used to hold the failure as a finished English string. Two
 * things went wrong with that on the SM-S737TL (Android 8.1, storage full): the
 * one line that explained the failure was hardcoded English, and a localized
 * string resolved in the view model is resolved in whatever language the
 * startup screen had rotated to at that instant. So the cause is kept here as
 * data, and the screen renders it in the user's own language.
 *
 * [detail] is the English text, for logs and the debug bundle a person sends
 * to support; it is never the only thing the screen shows for a known cause.
 */
sealed class StartupError {
    abstract val detail: String

    /** A failure with no known cause: its text is all there is. */
    data class Raw(override val detail: String) : StartupError()

    /** The services did not all come up within the startup wait. */
    data class ServicesTimeout(val online: Int, val total: Int) : StartupError() {
        override val detail: String get() = "Timeout waiting for services ($online/$total online)"
    }

    /**
     * The agent exited with a known cause: a `[FATAL_CODE]` line followed by
     * its `[FATAL_EXIT]`. [detail] is the FATAL_EXIT text.
     */
    data class Fatal(val code: FatalCode, override val detail: String) : StartupError()
}

/**
 * The agent's `[FATAL_CODE] <code> key=value …` line (CIRISAgent 2.17.0).
 *
 * Codes are stable snake_case; values are single tokens, printed as the agent
 * formats them (`free_mb=22.2 required_mb=50 ciris_mb=531.4`). The line names
 * the cause and never ends startup by itself: the `[FATAL_EXIT]` that follows
 * it does.
 */
data class FatalCode(val code: String, val params: Map<String, String>) {
    companion object {
        const val DISK_SPACE = "disk_space"

        private val LINE = Regex("""\[FATAL_CODE\]\s+([a-z][a-z0-9_]*)(.*)$""")
        private val PARAM = Regex("""([a-z][a-z0-9_]*)=(\S+)""")

        fun parse(line: String): FatalCode? {
            val m = LINE.find(line) ?: return null
            val params = PARAM.findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
            return FatalCode(m.groupValues[1], params)
        }
    }
}

/**
 * The localization key and params that explain [error], or null where only the
 * raw [StartupError.detail] can (an unknown fatal code, or one missing a value
 * its sentence needs).
 */
data class StartupErrorText(val key: String, val params: Map<String, String>, val hintKey: String? = null)

fun StartupError.localizedText(): StartupErrorText? = when (this) {
    is StartupError.Raw -> null
    is StartupError.ServicesTimeout -> StartupErrorText(
        "mobile.startup_services_timeout",
        mapOf("online" to online.toString(), "total" to total.toString()),
    )
    is StartupError.Fatal -> when (code.code) {
        FatalCode.DISK_SPACE -> {
            val free = code.params["free_mb"]
            val required = code.params["required_mb"]
            if (free == null || required == null) {
                null
            } else if (code.params["ciris_mb"] != null) {
                StartupErrorText(
                    "mobile.startup_fatal_disk_space",
                    mapOf("free" to free, "required" to required, "ciris" to code.params.getValue("ciris_mb")),
                    hintKey = "mobile.startup_fatal_disk_space_hint",
                )
            } else {
                StartupErrorText(
                    "mobile.startup_fatal_disk_space_short",
                    mapOf("free" to free, "required" to required),
                    hintKey = "mobile.startup_fatal_disk_space_hint",
                )
            }
        }
        else -> null
    }
}
