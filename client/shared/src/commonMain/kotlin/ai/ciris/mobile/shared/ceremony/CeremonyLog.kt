package ai.ciris.mobile.shared.ceremony

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * THE CEREMONY'S OWN RECORD (CSD-069). One JSON object per step, written to the app
 * log and appended to `ceremony-log.jsonl` at the root of the ceremony directory, so
 * the record travels with the stick. The tool reads only ceremony.json, terms.json and
 * its own subdirectories, so an extra file at D's root is ignored (confirmed from
 * ceremony_dir.rs by the server session, 2026-11-21).
 *
 * What a record never holds: the PIN (an interaction says `<pin entered>`), any input
 * the screen refused (`<rejected input>`), and the contents of a holder's sealed halt
 * (its path and hash only). The transcript has already been through [PinEchoGuard].
 */
class CeremonyStepRecord(
    val step: CeremonyStep,
    /** The args as run (from the subcommand on); the PIN is never an argument. */
    val args: List<String>,
    val tool: CeremonyToolInfo?,
    val startedAtMs: Long,
) {
    private val interactions = mutableListOf<JsonObject>()
    private val transcript = StringBuilder()

    fun output(text: String) {
        transcript.append(text)
    }

    fun transcript(): String = transcript.toString()

    fun prompted(atMs: Long, prompt: String) {
        interactions += buildJsonObject { put("at_ms", atMs); put("prompt", prompt) }
    }

    /** [what] is `yes`, `swapped`, [PIN_ENTERED] or [REJECTED_INPUT]; never what was typed otherwise. */
    fun relayed(atMs: Long, what: String) {
        require(what in RELAYED) { "only the fixed tokens are recorded" }
        interactions += buildJsonObject { put("at_ms", atMs); put("answer", what) }
    }

    fun toJson(
        endedAtMs: Long,
        exitCode: Int?,
        dirBefore: Map<String, FileFacts>,
        dirAfter: Map<String, FileFacts>,
        sealed: Map<String, FileFacts>,
    ): JsonObject {
        val out = transcript.toString()
        val fields = CeremonyFields.parse(out)
        return buildJsonObject {
            put("record", "ciris.genesis-ceremony.step/1")
            put("step", step.subcommand)
            put("args", JsonArray(args.map(::JsonPrimitive)))
            tool?.let { t ->
                put("tool", buildJsonObject {
                    put("version", t.version); put("commit", t.commit); put("sha256", t.sha256); put("size_bytes", t.sizeBytes)
                })
            }
            put("started_at_ms", startedAtMs)
            put("ended_at_ms", endedAtMs)
            put("exit_code", exitCode)
            put("output", out)
            put("output_note", "stdout and stderr together: the tool runs under one terminal")
            put("interactions", JsonArray(interactions.toList()))
            put("fields", buildJsonObject {
                put("serials", JsonArray(fields.serials.map(::JsonPrimitive)))
                put("identities", JsonArray(fields.identities.map(::JsonPrimitive)))
                fields.bundle?.let { put("bundle_object_id", it) }
            })
            put("changed", buildJsonArray {
                dirAfter.filter { (p, f) -> dirBefore[p]?.sha256 != f.sha256 }.forEach { (p, f) ->
                    add(buildJsonObject { put("path", p); put("sha256", f.sha256) })
                }
            })
            put("listing", buildJsonArray {
                dirAfter.forEach { (p, f) -> add(buildJsonObject { put("path", p); put("size", f.size); put("sha256", f.sha256) }) }
            })
            put("sealed", buildJsonArray {
                sealed.forEach { (p, f) -> add(buildJsonObject { put("path", p); put("sha256", f.sha256) }) }
            })
        }
    }

    companion object {
        const val PIN_ENTERED = "<pin entered>"
        const val REJECTED_INPUT = "<rejected input>"
        val RELAYED = setOf(CeremonyAnswer.YES.logged, CeremonyAnswer.SWAPPED.logged, PIN_ENTERED, REJECTED_INPUT)
        const val LOG_FILE = "ceremony-log.jsonl"
    }
}

data class FileFacts(val size: Long, val sha256: String?)

/** The serials, identities and bundle id a step printed, as fields. */
data class CeremonyFields(val serials: List<String>, val identities: List<String>, val bundle: String?) {
    companion object {
        private val SERIAL = Regex("""serial (\d+)""")
        private val IDENTITY = Regex("""(?:identity|identity_ref)\s+([A-Za-z][A-Za-z0-9+.\-]*:[^\s,()]+)""")
        private val URN = Regex("""\burn:[A-Za-z0-9][A-Za-z0-9\-]*:[^\s,()]+""")
        private val BUNDLE = Regex("""bundle ([0-9a-f]{64})""")

        fun parse(out: String) = CeremonyFields(
            serials = SERIAL.findAll(out).map { it.groupValues[1] }.distinct().toList(),
            identities = (IDENTITY.findAll(out).map { it.groupValues[1] } + URN.findAll(out).map { it.value }).distinct().toList(),
            bundle = BUNDLE.findAll(out).lastOrNull()?.groupValues?.get(1),
        )
    }
}

object CeremonySnapshot {
    /** Every file under [dir] but the log itself, with size and hash. */
    fun of(fs: CeremonyFs, dir: String): Map<String, FileFacts> =
        if (!fs.isDirectory(dir)) emptyMap()
        else fs.walk(dir).filter { it != CeremonyStepRecord.LOG_FILE }.associateWith {
            val p = fs.join(dir, it)
            FileFacts(fs.size(p), fs.sha256(p))
        }

    /** A sealed-halt directory: path and hash of each file, nothing else (its contents are never read into the log). */
    fun sealed(fs: CeremonyFs, dirs: List<String>): Map<String, FileFacts> =
        dirs.filter { it.isNotBlank() && fs.isDirectory(it) }.flatMap { d ->
            fs.walk(d).map { rel -> fs.join(d, rel).let { p -> p to FileFacts(fs.size(p), fs.sha256(p)) } }
        }.toMap()
}

/** Writes a record to both sinks: the app log and the ceremony directory's own log, append-only. */
class CeremonyLogWriter(private val fs: CeremonyFs, private val appLog: (String) -> Unit) {
    fun write(dir: String, record: JsonObject) {
        val line = record.toString()
        appLog("[genesis-ceremony] $line")
        if (fs.isDirectory(dir)) fs.appendLine(fs.join(dir, CeremonyStepRecord.LOG_FILE), line)
    }
}
