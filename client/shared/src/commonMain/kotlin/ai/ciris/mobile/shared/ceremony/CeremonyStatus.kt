package ai.ciris.mobile.shared.ceremony

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** The few file reads the ceremony screen needs; the desktop host implements them over java.io. */
interface CeremonyFs {
    fun exists(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun readText(path: String): String?
    /** The names directly under [path], or empty. */
    fun list(path: String): List<String>
    /** The parent directory of [path], or null. */
    fun parent(path: String): String?
    /** [child] joined under [dir]. */
    fun join(dir: String, child: String): String
    /** Whether [path] is [dir] or inside it, after resolving both. */
    fun isWithin(path: String, dir: String): Boolean
    /** Every regular file under [dir], as paths relative to it, sorted. */
    fun walk(dir: String): List<String>
    fun size(path: String): Long
    /** The file's SHA-256, lower-case hex, or null when it cannot be read. */
    fun sha256(path: String): String?
    /** Append [line] and a newline; the file is opened in append mode only, never truncated. */
    fun appendLine(path: String, line: String)
}

/**
 * WHERE THE CEREMONY IS, read from its directory alone (CSD-069), so the screen is
 * right after a restart and after the stick has been to another machine. The layout
 * is the tool's (ceremony_dir.rs): ceremony.json; holders/<n>/{public,row_id}.hex,
 * serial.txt, recovery.json and record.cose; seats/<n>/{record,binding}.cose;
 * terms.json; voices/<n>.cose. A holder's sealed halt is on their own medium, never here.
 */
data class CeremonyStatus(
    val initialised: Boolean,
    val familyKeyId: String? = null,
    val t0Ms: Long? = null,
    val holders: List<Holder> = emptyList(),
    val seats: List<Seat> = emptyList(),
    val termsFixed: Boolean = false,
    val bundleObjectId: String? = null,
    val termsHolders: List<String> = emptyList(),
) {
    data class Holder(
        val name: String,
        val prepared: Boolean,
        val serial: String?,
        val recoveryRecorded: Boolean,
        val recordSigned: Boolean,
        val voiced: Boolean,
    ) {
        /** One sitting done: the record and the voice are in D (the halt went to the holder's medium). */
        val signed: Boolean get() = recordSigned && voiced
    }

    data class Seat(val name: String, val written: Boolean)

    val voices: Int get() = holders.count { it.voiced }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun read(fs: CeremonyFs, dir: String): CeremonyStatus {
            val cfg = fs.readText(fs.join(dir, "ceremony.json")) ?: return CeremonyStatus(initialised = false)
            val c = runCatching { json.parseToJsonElement(cfg) as JsonObject }.getOrNull()
            val holdersDir = fs.join(dir, "holders")
            val holders = fs.list(holdersDir).sorted().filter { fs.isDirectory(fs.join(holdersDir, it)) }.map { n ->
                val h = fs.join(holdersDir, n)
                Holder(
                    name = n,
                    prepared = fs.exists(fs.join(h, "public.hex")) && fs.exists(fs.join(h, "row_id.hex")),
                    serial = fs.readText(fs.join(h, "serial.txt"))?.trim()?.takeIf { it.isNotEmpty() },
                    recoveryRecorded = fs.exists(fs.join(h, "recovery.json")),
                    recordSigned = fs.exists(fs.join(h, "record.cose")),
                    voiced = fs.exists(fs.join(fs.join(dir, "voices"), "$n.cose")),
                )
            }
            val seatsDir = fs.join(dir, "seats")
            val seats = fs.list(seatsDir).sorted().filter { fs.isDirectory(fs.join(seatsDir, it)) }.map { n ->
                val s = fs.join(seatsDir, n)
                Seat(n, fs.exists(fs.join(s, "record.cose")) && fs.exists(fs.join(s, "binding.cose")))
            }
            val terms = fs.readText(fs.join(dir, "terms.json"))?.let { runCatching { json.parseToJsonElement(it) as JsonObject }.getOrNull() }
            return CeremonyStatus(
                initialised = true,
                familyKeyId = (c?.get("family_key_id") as? JsonPrimitive)?.contentOrNull,
                t0Ms = (c?.get("t0_ms") as? JsonPrimitive)?.longOrNull,
                holders = holders,
                seats = seats,
                termsFixed = terms != null,
                bundleObjectId = (terms?.get("bundle_object_id") as? JsonPrimitive)?.contentOrNull,
                termsHolders = (terms?.get("holders") as? JsonArray)?.mapNotNull {
                    ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull
                }.orEmpty(),
            )
        }
    }
}

/**
 * WHAT MUST BE TRUE BEFORE A STEP STARTS (CSD-069). The tool refuses all of these
 * itself; the screen asks first so a person is told to mount the right stick before
 * a YubiKey is ever asked for. Each answer is a localisation key, or null when ready.
 */
object CeremonyPreflight {
    private val T0 = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,3})?Z$""")

    fun init(fs: CeremonyFs, dir: String, t0: String): String? = when {
        dir.isBlank() -> "mobile.genesis_pre_no_dir"
        !T0.matches(t0.trim()) -> "mobile.genesis_pre_t0_form"
        fs.parent(dir)?.let { fs.isDirectory(it) } == false -> "mobile.genesis_pre_dir_parent_missing"
        else -> null
    }

    /** Both sticks mounted at the start; only the YubiKeys are swapped mid-step. */
    fun holderPrep(fs: CeremonyFs, p: CeremonyCommands.HolderPrep): String? = when {
        !fs.exists(fs.join(p.dir, "ceremony.json")) -> "mobile.genesis_pre_not_initialised"
        p.name.isBlank() -> "mobile.genesis_pre_no_name"
        p.mldsaSeed.isBlank() || p.backupMldsaSeed.isBlank() -> "mobile.genesis_pre_seed_paths"
        p.mldsaSeed.trim() == p.backupMldsaSeed.trim() -> "mobile.genesis_pre_same_seed"
        fs.parent(p.mldsaSeed)?.let { fs.isDirectory(it) } != true -> "mobile.genesis_pre_holder_medium_missing"
        fs.parent(p.backupMldsaSeed)?.let { fs.isDirectory(it) } != true -> "mobile.genesis_pre_backup_medium_missing"
        p.keygen && fs.exists(p.mldsaSeed) -> "mobile.genesis_pre_seed_exists"
        !p.keygen && !fs.exists(p.mldsaSeed) -> "mobile.genesis_pre_seed_missing"
        p.backupKeygen && fs.exists(p.backupMldsaSeed) -> "mobile.genesis_pre_backup_seed_exists"
        !p.backupKeygen && !fs.exists(p.backupMldsaSeed) -> "mobile.genesis_pre_backup_seed_missing"
        fs.isWithin(p.mldsaSeed, p.dir) || fs.isWithin(p.backupMldsaSeed, p.dir) -> "mobile.genesis_pre_seed_in_dir"
        p.intermediates.any { it.isNotBlank() && !fs.exists(it) } -> "mobile.genesis_pre_intermediate_missing"
        else -> null
    }

    fun seat(fs: CeremonyFs, dir: String, name: String, seatSeed: String, transport: String, dial: List<String>): String? = when {
        !fs.exists(fs.join(dir, "ceremony.json")) -> "mobile.genesis_pre_not_initialised"
        name.isBlank() -> "mobile.genesis_pre_no_name"
        !fs.exists(seatSeed) -> "mobile.genesis_pre_seat_seed_missing"
        !fs.exists(transport) -> "mobile.genesis_pre_transport_missing"
        dial.none { it.trim().startsWith("tcp:") } -> "mobile.genesis_pre_no_tcp"
        else -> null
    }

    fun terms(fs: CeremonyFs, dir: String): String? =
        if (!fs.exists(fs.join(dir, "ceremony.json"))) "mobile.genesis_pre_not_initialised" else null

    fun holderSign(fs: CeremonyFs, dir: String, name: String, mldsaSeed: String, sealedDir: String): String? = when {
        !fs.exists(fs.join(dir, "terms.json")) -> "mobile.genesis_pre_no_terms"
        name.isBlank() -> "mobile.genesis_pre_no_name"
        !fs.exists(mldsaSeed) -> "mobile.genesis_pre_seed_missing"
        sealedDir.isBlank() || !fs.isDirectory(sealedDir) -> "mobile.genesis_pre_sealed_missing"
        fs.isWithin(sealedDir, dir) -> "mobile.genesis_pre_sealed_in_dir"
        else -> null
    }

    /** All holders' sealed sticks mounted: each listed directory exists. */
    fun halts(fs: CeremonyFs, dir: String, halts: List<String>): String? {
        val listed = halts.filter { it.isNotBlank() }
        return when {
            !fs.exists(fs.join(dir, "terms.json")) -> "mobile.genesis_pre_no_terms"
            listed.isEmpty() -> "mobile.genesis_pre_no_halts"
            listed.any { !fs.isDirectory(it.trim()) } -> "mobile.genesis_pre_halt_dir_missing"
            else -> null
        }
    }
}
