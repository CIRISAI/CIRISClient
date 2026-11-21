package ai.ciris.mobile.shared.ceremony

import ai.ciris.mobile.shared.viewmodels.GenesisCeremonyViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An in-memory directory tree. */
internal class MemFs : CeremonyFs {
    val files = linkedMapOf<String, String>()
    val dirs = mutableSetOf<String>()
    val appends = mutableListOf<Pair<String, String>>()
    private fun norm(p: String) = p.trimEnd('/')
    fun put(path: String, text: String) {
        files[norm(path)] = text
        var d = parent(path)
        while (d != null && d.isNotEmpty()) { dirs += d; d = parent(d) }
    }
    override fun exists(path: String) = norm(path) in files || norm(path) in dirs
    override fun isDirectory(path: String) = norm(path) in dirs
    override fun readText(path: String) = files[norm(path)]
    override fun list(path: String): List<String> = (files.keys + dirs).filter { parent(it) == norm(path) }.map { it.substringAfterLast('/') }.distinct()
    override fun parent(path: String): String? = norm(path).substringBeforeLast('/', "").ifEmpty { null }
    override fun join(dir: String, child: String) = "${norm(dir)}/$child"
    override fun isWithin(path: String, dir: String) = norm(path) == norm(dir) || norm(path).startsWith(norm(dir) + "/")
    override fun walk(dir: String) = files.keys.filter { it.startsWith(norm(dir) + "/") }.map { it.removePrefix(norm(dir) + "/") }.sorted()
    override fun size(path: String) = (files[norm(path)] ?: "").length.toLong()
    override fun sha256(path: String) = files[norm(path)]?.hashCode()?.toString(16)
    override fun appendLine(path: String, line: String) {
        appends += norm(path) to line
        files[norm(path)] = (files[norm(path)] ?: "") + line + "\n"
    }
}

/** A runner whose tool echoes whatever it is sent, the PIN included: the worst case. */
internal class EchoRunner(override val fs: CeremonyFs, override val bundled: Boolean = true) : CeremonyRunner {
    var last: EchoRun? = null
    override suspend fun prepare(): CeremonyToolState = CeremonyToolState.Ready(CeremonyToolInfo("genesis-ceremony 0.1.0", "0dabc27e", "ab".repeat(32), 1))
    override fun run(step: CeremonyStep, args: List<String>, externalSigner: CeremonyExternalSigner?): CeremonyRun = EchoRun().also { last = it }

    inner class EchoRun : CeremonyRun {
        val ch = Channel<CeremonyEvent>(Channel.UNLIMITED)
        override val events: Flow<CeremonyEvent> = ch.receiveAsFlow()
        var pending: CeremonyOutput.Prompt? = null
        val sent = mutableListOf<String>()
        fun say(text: String) {
            ch.trySend(CeremonyEvent.Output(text))
            CeremonyOutput.pendingPrompt(text)?.let { pending = it; ch.trySend(CeremonyEvent.Prompt(it)) }
        }
        fun exit(code: Int) { ch.trySend(CeremonyEvent.Exited(code)); ch.close() }
        override fun answer(answer: CeremonyAnswer): Boolean {
            val ok = (answer == CeremonyAnswer.YES && pending == CeremonyOutput.Prompt.CONFIRM) ||
                (answer == CeremonyAnswer.SWAPPED && pending == CeremonyOutput.Prompt.SWAP_TOKEN)
            if (ok) { sent += answer.logged; pending = null; ch.trySend(CeremonyEvent.Output((if (answer == CeremonyAnswer.YES) "yes" else "") + "\n")) }
            return ok
        }
        override fun pin(pin: CharArray): Boolean {
            if (pending != CeremonyOutput.Prompt.PIN) { pin.fill('\u0000'); return false }
            // The worst case: the terminal echoes the PIN back.
            ch.trySend(CeremonyEvent.Output(pin.concatToString() + "\nPIN accepted.\n"))
            sent += "<pin>"
            pin.fill('\u0000')
            pending = null
            return true
        }
        override fun cancel() = exit(130)
    }
}

class GenesisCeremonyViewModelTest {
    private val dir = "/media/coord/D"
    private val pin = "246813"

    private fun setup(scope: TestScope): Triple<GenesisCeremonyViewModel, EchoRunner, MutableList<String>> {
        val fs = MemFs().apply {
            put("$dir/ceremony.json", """{"family_key_id":"humanity-accord","t0_ms":1}""")
            put("$dir/terms.json", """{"bundle_object_id":"${"cd".repeat(32)}","holders":[{"name":"A1"}]}""")
            put("/media/A1/mldsa65.seed", "seed")
            dirs += "/media/A1/sealed"
        }
        val runner = EchoRunner(fs)
        val appLog = mutableListOf<String>()
        var t = 1000L
        val vm = GenesisCeremonyViewModel(runner, scope, { t++ }) { appLog += it }
        vm.setDir(dir)
        vm.prepare()
        return Triple(vm, runner, appLog)
    }

    @Test fun the_pin_is_in_neither_sink_even_when_echoed() = runTest(StandardTestDispatcher()) {
        val (vm, runner, appLog) = setup(this)
        advanceUntilIdle()
        vm.runHolderSign("A1", "/media/A1/mldsa65.seed", "/media/A1/sealed", null)
        val run = runner.last!!
        run.say("YubiKey PIV PIN (slot 9c): ")
        advanceUntilIdle()
        vm.sendPin(pin.toCharArray())
        advanceUntilIdle()
        run.say("ONE SITTING for holder A1\nType yes to sign (do NOT touch the YubiKey yet): ")
        advanceUntilIdle()
        vm.answerTyped("yes")
        run.say(">>> Touch the YubiKey now (1 of 3). <<<\n")
        run.exit(0)
        advanceUntilIdle()
        val fileLog = (runner.fs as MemFs).files["$dir/${CeremonyStepRecord.LOG_FILE}"].orEmpty()
        assertTrue(fileLog.isNotEmpty() && appLog.isNotEmpty(), "both sinks were written")
        assertFalse(pin in fileLog, "the PIN is not in ceremony-log.jsonl")
        assertFalse(appLog.any { pin in it }, "the PIN is not in the app log")
        assertFalse(pin in (vm.running.value?.transcript ?: ""), "the PIN is not on screen")
        assertTrue("<pin entered>" in fileLog && "\"answer\":\"yes\"" in fileLog)
        assertEquals(listOf("<pin>", "yes"), run.sent)
    }

    @Test fun rejected_input_is_refused_and_never_logged_verbatim() = runTest(StandardTestDispatcher()) {
        val (vm, runner, appLog) = setup(this)
        advanceUntilIdle()
        vm.runTerms()
        val run = runner.last!!
        run.say("Type yes to sign (do NOT touch the YubiKey yet): ")
        advanceUntilIdle()
        val otp = "cccccbhkevrjnbljhhfgftfuvbrecekjtnkukklrgdjc"
        vm.answerTyped(otp)
        vm.answerTyped(pin) // a PIN typed into the answer field
        run.exit(1)
        advanceUntilIdle()
        assertTrue(run.sent.isEmpty(), "nothing reached the tool")
        val fileLog = (runner.fs as MemFs).files["$dir/${CeremonyStepRecord.LOG_FILE}"].orEmpty()
        listOf(otp, pin).forEach { s ->
            assertFalse(s in fileLog); assertFalse(appLog.any { s in it })
        }
        assertEquals(2, Regex("<rejected input>").findAll(fileLog).count())
    }

    @Test fun the_log_is_appended_never_rewritten() = runTest(StandardTestDispatcher()) {
        val (vm, runner, _) = setup(this)
        advanceUntilIdle()
        val fs = runner.fs as MemFs
        fs.put("$dir/${CeremonyStepRecord.LOG_FILE}", "{\"earlier\":true}\n")
        repeat(2) {
            vm.runTerms(); runner.last!!.say("genesis bundle ${"cd".repeat(32)}\n"); runner.last!!.exit(0); advanceUntilIdle()
        }
        val log = fs.files["$dir/${CeremonyStepRecord.LOG_FILE}"]!!.lines().filter { it.isNotBlank() }
        assertEquals(3, log.size)
        assertEquals("{\"earlier\":true}", log.first())
        assertTrue(fs.appends.size == 2 && fs.appends.all { it.first.endsWith(CeremonyStepRecord.LOG_FILE) })
        assertTrue("\"bundle_object_id\":\"${"cd".repeat(32)}\"" in log[1])
    }

    @Test fun a_step_does_not_start_without_its_media() = runTest(StandardTestDispatcher()) {
        val (vm, runner, _) = setup(this)
        advanceUntilIdle()
        vm.runHolderSign("A1", "/media/A1/mldsa65.seed", "/media/A1/nowhere", null)
        assertEquals("mobile.genesis_pre_sealed_missing", vm.preflight.value)
        vm.runHolderPrep(CeremonyCommands.HolderPrep(dir, "A1", "/media/A1/mldsa65.seed", false, emptyList(), "/media/A2/mldsa65.seed", false))
        assertEquals("mobile.genesis_pre_backup_medium_missing", vm.preflight.value)
        assertEquals(null, runner.last)
    }
}
