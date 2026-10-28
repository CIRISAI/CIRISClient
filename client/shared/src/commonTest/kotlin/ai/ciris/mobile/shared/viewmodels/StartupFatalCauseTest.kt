package ai.ciris.mobile.shared.viewmodels

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A startup failure says why, in the user's language.**
 *
 * SM-S737TL (Android 8.1, 22 MB free): the agent refused to start for lack of
 * storage, and the screen showed "Timeout waiting for services (0/22 online)"
 * in English, 180 s later, while rotating through languages that were not the
 * user's. CIRISAgent 2.17.0 names the cause on a `[FATAL_CODE]` line before
 * its `[FATAL_EXIT]`; these pin how the client reads it and what it shows.
 */
class StartupFatalCauseTest {

    private fun vm() = StartupViewModel(FakePythonRuntime(), FakeCIRISApiClient())

    /** The exact line CIRISAgent prints (fix/qa-desktop-input-settle, 2.17.0). */
    private val diskLine = "[FATAL_CODE] disk_space free_mb=22.2 required_mb=50 ciris_mb=531.4"

    @Test
    fun theAgentsDiskLineParsesWithItsValuesAsPrinted() {
        val code = FatalCode.parse(diskLine)!!
        assertEquals("disk_space", code.code)
        assertEquals(mapOf("free_mb" to "22.2", "required_mb" to "50", "ciris_mb" to "531.4"), code.params)
        // logcat prefixes the line; the parse must not depend on column 0.
        assertEquals(code, FatalCode.parse("I/python.stdout( 4242): $diskLine"))
    }

    @Test
    fun fatalCodeAndFatalExitAreDifferentLines() {
        assertNull(FatalCode.parse("[FATAL_EXIT] CIRIS cannot start: out of disk"))
        assertNull(FatalCode.parse("[FATAL] something"))
        assertEquals(FatalCode("some_new_code", emptyMap()), FatalCode.parse("[FATAL_CODE] some_new_code"))
    }

    @Test
    fun aFatalCodeAloneDoesNotEndStartup() {
        val vm = vm()
        assertTrue(vm.onFatalLine(diskLine))
        assertFalse(vm.hasError.value, "the code names a cause; only FATAL_EXIT ends startup")
        assertNull(vm.startupError.value)
    }

    @Test
    fun fatalExitAfterTheCodeIsExplainedByIt() {
        val vm = vm()
        vm.onFatalLine(diskLine)
        assertTrue(vm.onFatalLine("[FATAL_EXIT] CIRIS cannot start: only 22.2 MB free, 50 MB required"))
        assertEquals(StartupPhase.ERROR, vm.phase.value)
        val err = assertIs<StartupError.Fatal>(vm.startupError.value)
        assertEquals("disk_space", err.code.code)
        assertEquals("CIRIS cannot start: only 22.2 MB free, 50 MB required", vm.errorMessage.value)
        val text = err.localizedText()!!
        assertEquals("mobile.startup_fatal_disk_space", text.key)
        assertEquals(mapOf("free" to "22.2", "required" to "50", "ciris" to "531.4"), text.params)
        assertEquals("mobile.startup_fatal_disk_space_hint", text.hintKey)
    }

    @Test
    fun theLaterServicesTimeoutCannotReplaceTheCause() {
        val vm = vm()
        vm.onFatalLine(diskLine)
        vm.onFatalLine("[FATAL_EXIT] CIRIS cannot start")
        vm.onErrorDetected(StartupError.ServicesTimeout(0, 22))
        assertIs<StartupError.Fatal>(vm.startupError.value)
    }

    @Test
    fun aFatalExitWithNoCodeShowsItsOwnText() {
        val vm = vm()
        vm.onFatalLine("[FATAL_EXIT] CIRIS cannot start: something new")
        val err = assertIs<StartupError.Raw>(vm.startupError.value)
        assertNull(err.localizedText(), "no known cause: the raw text is the explanation")
        assertEquals("CIRIS cannot start: something new", err.detail)
    }

    @Test
    fun anUnknownCodeOrAMissingValueFallsBackToTheRawText() {
        assertNull(StartupError.Fatal(FatalCode("quota_exceeded", mapOf("x" to "1")), "raw").localizedText())
        assertNull(StartupError.Fatal(FatalCode("disk_space", mapOf("required_mb" to "50")), "raw").localizedText())
        val short = StartupError.Fatal(FatalCode("disk_space", mapOf("free_mb" to "1.0", "required_mb" to "50")), "raw")
            .localizedText()!!
        assertEquals("mobile.startup_fatal_disk_space_short", short.key)
    }

    @Test
    fun theServicesTimeoutIsLocalizedNotEnglish() {
        val vm = vm()
        vm.onErrorDetected(StartupError.ServicesTimeout(0, 22))
        val text = vm.startupError.value!!.localizedText()!!
        assertEquals("mobile.startup_services_timeout", text.key)
        assertEquals(mapOf("online" to "0", "total" to "22"), text.params)
        assertEquals("Timeout waiting for services (0/22 online)", vm.errorMessage.value, "the bundle keeps the English detail")
    }

    @Test
    fun errorIsASettledPhaseSoTheLanguageStopsRotating() {
        assertTrue(StartupPhase.ERROR.settled)
        assertTrue(StartupPhase.READY.settled)
        assertTrue(StartupPhase.FIRST_RUN_SETUP.settled)
        StartupPhase.entries
            .filter { it != StartupPhase.ERROR && it != StartupPhase.READY && it != StartupPhase.FIRST_RUN_SETUP }
            .forEach { assertFalse(it.settled, "$it is still starting and may rotate") }
    }
}
