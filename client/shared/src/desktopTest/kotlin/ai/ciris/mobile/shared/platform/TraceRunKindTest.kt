package ai.ciris.mobile.shared.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CIRISClient#182: the Android host turns the harness's
 * `setprop debug.ciris.trace_run_kind qa` into CIRIS_TRACE_RUN_KIND for the
 * embedded Python. What it hands over is a label, never a shell string.
 */
class TraceRunKindTest {
    @Test
    fun aHarnessDeclarationIsHandedOver() {
        assertEquals("qa", TraceRunKind.fromDeclared("qa\n"))
        assertEquals("benchmark", TraceRunKind.fromDeclared(" Benchmark "))
    }

    @Test
    fun noDeclarationMeansNothingIsSet() {
        // A real install has no property; the agent's own default (production) stands.
        assertNull(TraceRunKind.fromDeclared(null))
        assertNull(TraceRunKind.fromDeclared(""))
        assertNull(TraceRunKind.fromDeclared("''"))
    }

    @Test
    fun anythingThatIsNotALabelIsRefused() {
        assertNull(TraceRunKind.fromDeclared("qa; rm -rf /"))
        assertNull(TraceRunKind.fromDeclared("x".repeat(40)))
    }
}
