package ai.ciris.mobile.shared.ceremony

import kotlinx.coroutines.flow.Flow

/** The steps the Ceremony screen runs (CEREMONY.md §3 steps 1–5, CSD-069). */
enum class CeremonyStep(val subcommand: String) {
    INIT("init"),
    HOLDER_PREP("holder-prep"),
    SEAT("seat"),
    TERMS("terms"),
    HOLDER_SIGN("holder-sign"),
    CHECK("check"),
    FINAL_GATE("final-gate"),
}

/** What the person answers at a prompt. Only these reach the tool: anything else is refused before it. */
enum class CeremonyAnswer(val logged: String) {
    /** The typed `yes` at "Type yes to sign". */
    YES("yes"),
    /** The holder token is out and the backup token is in (holder-prep's mid-step prompt). */
    SWAPPED("swapped"),
}

/** Which tool will run, as the start screen shows it. */
data class CeremonyToolInfo(
    /** `--version`'s answer, or null when the tool has none. */
    val version: String?,
    val commit: String,
    val sha256: String,
    val sizeBytes: Long,
)

/** Whether a tool is there to run, and if not, why (a localisation key and plain detail). */
sealed interface CeremonyToolState {
    data class Ready(val info: CeremonyToolInfo) : CeremonyToolState
    /** This build carries no ceremony tool. */
    data object NotBundled : CeremonyToolState
    /** This platform cannot run it (e.g. no terminal for the PIN). */
    data class Unsupported(val detail: String) : CeremonyToolState
    /** The tool is there but refused: its hash is wrong, or it lacks a subcommand. */
    data class Refused(val key: String, val detail: String) : CeremonyToolState
}

/** What a running step says, in order. */
sealed interface CeremonyEvent {
    /** Text the tool printed (stdout and stderr; a terminal merges them). Never the PIN. */
    data class Output(val text: String) : CeremonyEvent
    /** The tool is waiting on [prompt]. */
    data class Prompt(val prompt: CeremonyOutput.Prompt) : CeremonyEvent
    /** "Touch the YubiKey now (n of of)". */
    data class Touch(val n: Int, val of: Int) : CeremonyEvent
    /**
     * The ceremony needs an Ed25519 signature from a platform signer (see
     * [CeremonyRunner.run]'s `externalSigner`). Never emitted by the desktop tool.
     */
    data class SignRequest(val representative: ByteArray, val serialHint: String?) : CeremonyEvent
    data class Exited(val code: Int) : CeremonyEvent
}

/**
 * An Ed25519 half held by the platform, for a runner whose ceremony runs in-process.
 *
 * WHY IT IS HERE NOW: the desktop tool signs inside its own process (verify's
 * SplitCompositeSigner over the medium's ML-DSA seed and the YubiKey through PKCS#11),
 * so the desktop runner never calls this. Server 0.6's in-process `ciris-server
 * ceremony` will run on mobile too, where the YubiKey is reached over NFC or USB-C by
 * the existing YubiKeyPivSigner. There the ceremony drafts a statement, hands the
 * 127-byte message representative here, and assembles the composite signature from
 * the answer: the same external-Ed25519 shape. Keeping it in the interface means that
 * runner drops in without a change to the screen. Not wired to YubiKeyPivSigner yet.
 */
fun interface CeremonyExternalSigner {
    /** Sign [representative] with the holder token's Ed25519 key; [pin] is wiped by the callee. */
    suspend fun signEd25519(representative: ByteArray, pin: CharArray?): ByteArray
}

/** One running step. */
interface CeremonyRun {
    val events: Flow<CeremonyEvent>

    /** Relay [answer] to the tool. False when the tool is not waiting on the prompt it answers. */
    fun answer(answer: CeremonyAnswer): Boolean

    /**
     * Relay the PIN to the tool's own PIN prompt, then wipe [pin]. Only while the tool is
     * waiting on [CeremonyOutput.Prompt.PIN]; otherwise it is wiped and nothing is sent.
     */
    fun pin(pin: CharArray): Boolean

    fun cancel()
}

/**
 * RUNS THE CEREMONY TOOL (CSD-069). The screen depends on this and nothing else, so the
 * tool's home can change without a UI change:
 * - tonight's LOCAL desktop build runs a bundled `genesis-ceremony` binary under a
 *   pseudo-terminal (desktopMain). Release builds never bundle it (-PceremonyBinary is
 *   never set for a release), so no wheel and no published jar carries it;
 * - the released form is server 0.6's in-process `ciris-server ceremony`, desktop and
 *   mobile.
 */
interface CeremonyRunner {
    /** Whether this build carries a ceremony tool at all. False: the entry is not drawn. */
    val bundled: Boolean

    /** Check the tool before anything runs: present, hash verified, every subcommand listed. */
    suspend fun prepare(): CeremonyToolState

    /** The files the screen reads and the log it appends to. */
    val fs: CeremonyFs

    /**
     * Start [step] with [args] (from [CeremonyCommands], starting at the subcommand). A
     * runner whose ceremony signs in-process on a device with no PKCS#11 asks
     * [externalSigner] for the Ed25519 half; the desktop tool ignores it.
     */
    fun run(step: CeremonyStep, args: List<String>, externalSigner: CeremonyExternalSigner? = null): CeremonyRun
}

/** The platform's runner, or null where there is none yet (mobile and web until server 0.6). */
expect fun platformCeremonyRunner(): CeremonyRunner?
