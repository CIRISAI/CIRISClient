package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.ceremony.CeremonyAnswer
import ai.ciris.mobile.shared.ceremony.CeremonyCommands
import ai.ciris.mobile.shared.ceremony.CeremonyEvent
import ai.ciris.mobile.shared.ceremony.CeremonyIntermediates
import ai.ciris.mobile.shared.ceremony.CeremonyLogWriter
import ai.ciris.mobile.shared.ceremony.CeremonyOutput
import ai.ciris.mobile.shared.ceremony.CeremonyPreflight
import ai.ciris.mobile.shared.ceremony.CeremonyRun
import ai.ciris.mobile.shared.ceremony.CeremonyRunner
import ai.ciris.mobile.shared.ceremony.CeremonySnapshot
import ai.ciris.mobile.shared.ceremony.CeremonyStatus
import ai.ciris.mobile.shared.ceremony.CeremonyStep
import ai.ciris.mobile.shared.ceremony.CeremonyStepRecord
import ai.ciris.mobile.shared.ceremony.CeremonyToolState
import ai.ciris.mobile.shared.ceremony.PinEchoGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * THE GENESIS CEREMONY SCREEN'S STATE (CSD-069). It drives a [CeremonyRunner] step by
 * step over one ceremony directory and relays only what each prompt expects. It signs
 * nothing and reads no key: the tool does both. Where the ceremony is comes from the
 * directory ([CeremonyStatus]), never from memory, so it is right after a restart.
 */
class GenesisCeremonyViewModel(
    private val runner: CeremonyRunner?,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    appLog: (String) -> Unit,
) {
    data class Running(
        val step: CeremonyStep,
        val line: String,
        val transcript: String = "",
        val prompt: CeremonyOutput.Prompt? = null,
        val touch: CeremonyOutput.Touch? = null,
        val otpTyped: Boolean = false,
        val exitCode: Int? = null,
        /** The tool's own error text, shown verbatim. */
        val error: String? = null,
        val refusal: CeremonyOutput.Refusal? = null,
        val finalGatePassed: Boolean = false,
        /** The last thing typed in the answer field was not what the prompt expects, so nothing was sent. */
        val inputRejected: Boolean = false,
    ) {
        val finished: Boolean get() = exitCode != null
    }

    private val _tool = MutableStateFlow<CeremonyToolState?>(if (runner == null) CeremonyToolState.Unsupported("no runner on this platform") else null)
    val tool: StateFlow<CeremonyToolState?> = _tool.asStateFlow()

    private val _dir = MutableStateFlow("")
    val dir: StateFlow<String> = _dir.asStateFlow()

    private val _status = MutableStateFlow(CeremonyStatus(initialised = false))
    val status: StateFlow<CeremonyStatus> = _status.asStateFlow()

    private val _running = MutableStateFlow<Running?>(null)
    val running: StateFlow<Running?> = _running.asStateFlow()

    /** Why a step did not start (a localisation key), or null. */
    private val _preflight = MutableStateFlow<String?>(null)
    val preflight: StateFlow<String?> = _preflight.asStateFlow()

    /** Plain detail for [preflight], when there is one (e.g. which intermediate is missing). */
    private val _preflightDetail = MutableStateFlow<String?>(null)
    val preflightDetail: StateFlow<String?> = _preflightDetail.asStateFlow()

    private val writer = runner?.let { CeremonyLogWriter(it.fs, appLog) }
    private var run: CeremonyRun? = null
    private var job: Job? = null
    private var record: CeremonyStepRecord? = null
    private val guard = PinEchoGuard()

    fun prepare() {
        val r = runner ?: return
        scope.launch { _tool.value = r.prepare() }
    }

    fun setDir(path: String) {
        _dir.value = path.trim()
        refresh()
    }

    fun refresh() {
        val r = runner ?: return
        val d = _dir.value
        _status.value = if (d.isBlank()) CeremonyStatus(initialised = false) else CeremonyStatus.read(r.fs, d)
    }

    val busy: Boolean get() = _running.value?.finished == false

    // ── The steps ────────────────────────────────────────────────────────────

    fun runInit(t0: String) = start(CeremonyStep.INIT, CeremonyCommands.init(_dir.value, t0.trim())) {
        CeremonyPreflight.init(it, _dir.value, t0)
    }

    /**
     * Holder-prep ALWAYS passes the two bundled Yubico intermediates first (PIV Attestation
     * B 1, then Attestation Intermediate B 1), then any [p]'s extra ones. Without both the
     * custody chain stops at f9 and the check refuses it, so the step does not start.
     */
    fun runHolderPrep(p: CeremonyCommands.HolderPrep) {
        val r = runner ?: return
        when (val im = r.custodyIntermediates()) {
            is CeremonyIntermediates.Ready -> {
                val q = p.copy(dir = _dir.value, intermediates = im.paths + p.intermediates.filter { it.isNotBlank() })
                start(CeremonyStep.HOLDER_PREP, CeremonyCommands.holderPrep(q)) { CeremonyPreflight.holderPrep(it, q) }
            }
            is CeremonyIntermediates.Missing -> { _preflight.value = "mobile.genesis_pre_intermediates_missing"; _preflightDetail.value = im.detail }
            is CeremonyIntermediates.Refused -> { _preflight.value = "mobile.genesis_pre_intermediates_refused"; _preflightDetail.value = im.detail }
        }
    }

    fun runSeat(name: String, seatSeed: String, transport: String, dial: List<String>) =
        start(CeremonyStep.SEAT, CeremonyCommands.seat(_dir.value, name.trim(), seatSeed.trim(), transport.trim(), dial)) {
            CeremonyPreflight.seat(it, _dir.value, name, seatSeed.trim(), transport.trim(), dial)
        }

    fun runTerms() = start(CeremonyStep.TERMS, CeremonyCommands.terms(_dir.value)) { CeremonyPreflight.terms(it, _dir.value) }

    fun runHolderSign(name: String, mldsaSeed: String, sealedDir: String, module: String?) =
        start(
            CeremonyStep.HOLDER_SIGN,
            CeremonyCommands.holderSign(_dir.value, name.trim(), mldsaSeed.trim(), sealedDir.trim(), module),
            sealed = listOf(sealedDir.trim()),
        ) { CeremonyPreflight.holderSign(it, _dir.value, name, mldsaSeed.trim(), sealedDir.trim()) }

    fun runCheck(halts: List<String>) = halts.map { it.trim() }.let { h ->
        start(CeremonyStep.CHECK, CeremonyCommands.check(_dir.value, h), sealed = h) { CeremonyPreflight.halts(it, _dir.value, h) }
    }

    fun runFinalGate(halts: List<String>) = halts.map { it.trim() }.let { h ->
        start(CeremonyStep.FINAL_GATE, CeremonyCommands.finalGate(_dir.value, h), sealed = h) { CeremonyPreflight.halts(it, _dir.value, h) }
    }

    private fun start(
        step: CeremonyStep,
        args: List<String>,
        sealed: List<String> = emptyList(),
        preflight: (ai.ciris.mobile.shared.ceremony.CeremonyFs) -> String?,
    ) {
        val r = runner ?: return
        if (busy) return
        if (_tool.value !is CeremonyToolState.Ready) {
            _preflight.value = "mobile.genesis_pre_tool_not_ready"
            return
        }
        preflight(r.fs)?.let { _preflight.value = it; _preflightDetail.value = null; return }
        _preflight.value = null
        _preflightDetail.value = null
        val d = _dir.value
        val before = CeremonySnapshot.of(r.fs, d)
        val rec = CeremonyStepRecord(step, args, (_tool.value as? CeremonyToolState.Ready)?.info, now())
        record = rec
        _running.value = Running(step, CeremonyCommands.shellLine(args))
        val handle = r.run(step, args)
        run = handle
        job = scope.launch {
            handle.events.collect { e ->
                when (e) {
                    is CeremonyEvent.Output -> {
                        val text = guard.scrub(e.text, now())
                        rec.output(text)
                        val t = rec.transcript()
                        _running.update { it?.copy(transcript = t, prompt = CeremonyOutput.pendingPrompt(t), touch = CeremonyOutput.lastTouch(t) ?: it.touch, otpTyped = CeremonyOutput.otpTyped(t)) }
                    }
                    is CeremonyEvent.Prompt -> {
                        rec.prompted(now(), e.prompt.name.lowercase())
                        _running.update { it?.copy(prompt = e.prompt) }
                    }
                    is CeremonyEvent.Touch -> _running.update { it?.copy(touch = CeremonyOutput.Touch(e.n, e.of)) }
                    is CeremonyEvent.SignRequest -> Unit // only an in-process runner on mobile asks (CeremonyExternalSigner)
                    is CeremonyEvent.Exited -> finish(rec, e.code, d, before, sealed)
                }
            }
        }
    }

    private fun finish(rec: CeremonyStepRecord, code: Int, d: String, before: Map<String, ai.ciris.mobile.shared.ceremony.FileFacts>, sealed: List<String>) {
        guard.wipe()
        val fs = runner?.fs ?: return
        val t = rec.transcript()
        val err = if (code != 0) CeremonyOutput.errorText(t) ?: t.trim().lines().takeLast(3).joinToString("\n") else null
        val json = rec.toJson(now(), code, before, CeremonySnapshot.of(fs, d), CeremonySnapshot.sealed(fs, sealed))
        writer?.write(d, json)
        _running.update {
            it?.copy(
                exitCode = code, prompt = null, error = err,
                refusal = err?.let(CeremonyOutput::refusalOf),
                finalGatePassed = rec.step == CeremonyStep.FINAL_GATE && code == 0 && CeremonyOutput.finalGatePassed(t),
            )
        }
        run = null
        refresh()
    }

    // ── What the person answers ──────────────────────────────────────────────

    /**
     * The answer field. Only the word the prompt expects is relayed: `yes` at the
     * confirmation, `swapped` at the token swap. Anything else (a YubiKey one-time
     * password from a stray touch, a PIN in the wrong field) is refused before it
     * reaches the tool and recorded only as `<rejected input>`.
     */
    fun answerTyped(text: String) {
        val h = run ?: return
        val rec = record ?: return
        val prompt = _running.value?.prompt
        val answer = when {
            prompt == CeremonyOutput.Prompt.CONFIRM && text.trim() == "yes" -> CeremonyAnswer.YES
            prompt == CeremonyOutput.Prompt.SWAP_TOKEN && text.trim().equals("swapped", ignoreCase = true) -> CeremonyAnswer.SWAPPED
            else -> null
        }
        if (answer == null || !h.answer(answer)) {
            rec.relayed(now(), CeremonyStepRecord.REJECTED_INPUT)
            _running.update { it?.copy(inputRejected = true) }
            return
        }
        rec.relayed(now(), answer.logged)
        _running.update { it?.copy(inputRejected = false, prompt = null) }
    }

    /** The swap button: the holder token is out and the backup token is in. */
    fun swapped() = answerTyped("swapped")

    /**
     * The PIN field. Sent only while the tool waits on its PIN prompt; [pin] is wiped
     * either way. The guard keeps a copy for a few seconds to mask any echo, then wipes it.
     */
    fun sendPin(pin: CharArray) {
        val h = run
        val rec = record
        if (h == null || rec == null || _running.value?.prompt != CeremonyOutput.Prompt.PIN) {
            pin.fill('\u0000')
            rec?.relayed(now(), CeremonyStepRecord.REJECTED_INPUT)
            return
        }
        guard.arm(pin.copyOf(), now())
        val sent = h.pin(pin) // wipes pin
        rec.relayed(now(), if (sent) CeremonyStepRecord.PIN_ENTERED else CeremonyStepRecord.REJECTED_INPUT)
        _running.update { it?.copy(prompt = null) }
    }

    fun cancel() {
        run?.cancel()
    }

    fun dismissRun() {
        if (!busy) _running.value = null
    }
}
