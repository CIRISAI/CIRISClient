package ai.ciris.mobile.shared.platform

/**
 * The trace run kind a harness declared for this run (CIRISClient#182).
 *
 * Every harness run labels its traces; only a run outside a harness is
 * `production`. Desktop and the iOS simulator receive CIRIS_TRACE_RUN_KIND as
 * an environment variable the embedded Python already reads. Android cannot —
 * `am start` sets no environment — so the harness declares it as the system
 * property [ANDROID_PROP] and CirisVerify.setup hands it over before Python
 * starts. Mirrors CIRISAgent tools/qa_runner/run_kind.py.
 */
object TraceRunKind {
    const val ENV_VAR = "CIRIS_TRACE_RUN_KIND"
    const val ANDROID_PROP = "debug.ciris.trace_run_kind"

    private val LABEL = Regex("[a-z][a-z0-9_]{0,31}")

    /** The label to export, or null when nothing (or nothing label-shaped) was declared. */
    fun fromDeclared(raw: String?): String? =
        raw?.trim()?.lowercase()?.takeIf { LABEL.matches(it) }
}
