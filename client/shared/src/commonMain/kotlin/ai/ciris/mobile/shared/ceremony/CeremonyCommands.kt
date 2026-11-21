package ai.ciris.mobile.shared.ceremony

/**
 * EVERY COMMAND LINE THE CEREMONY SCREEN RUNS (CSD-069), in one place.
 *
 * The v62 genesis is the offline `genesis-ceremony` tool (CIRISServer
 * tools/genesis-ceremony, 0dabc27e; CIRISConstitution formal/ceremony/CEREMONY.md §3).
 * The client signs nothing and reads no key: it runs these argv over the
 * ceremony directory and relays what the tool asks. They reproduce, byte for byte,
 * the lines the tool's README gives and its `--help` accepts, so a rehearsal in a
 * terminal and a run from this screen are the same run. CeremonyCommandsTest pins
 * each one.
 *
 * The argv here start at the subcommand: the runner supplies the tool (the desktop
 * build's bundled binary today; the node's in-process ceremony later), so these lines
 * do not change when the tool's home does.
 *
 * The dry-run flags (`--yes`, `--mock-*`, `--chain`) are never built here: the
 * ceremony build refuses them, and no screen offers them.
 */
object CeremonyCommands {
    /** The subcommands this screen calls; `--help` must list every one before anything runs. */
    val SUBCOMMANDS: List<String> = listOf("init", "holder-prep", "seat", "terms", "holder-sign", "check", "final-gate")

    /** The PIV slot the tool defaults to for both the holder and the backup token. */
    const val DEFAULT_SLOT = "9c"

    /** The dial hints of the first serve seat, most preferred first (CC 2.6.8; the seat signs them). */
    val CANONICAL_1_DIAL: List<String> = listOf(
        "tcp:canonical-1.ciris-services-1.ai:4242",
        "tcp:108.61.242.236:4242",
        "tcp:2001:19f0:5c00:4b0c:5400:5ff:fed2:3667:4242",
    )

    /** Step 0, the coordinator: `init --dir D --t0 T`. */
    fun init(dir: String, t0: String): List<String> =
        listOf("init", "--dir", dir, "--t0", t0)

    data class HolderPrep(
        val dir: String,
        val name: String,
        val mldsaSeed: String,
        val keygen: Boolean,
        val intermediates: List<String>,
        val backupMldsaSeed: String,
        val backupKeygen: Boolean,
    )

    /**
     * Step 1a, each holder, no PIN and no touch: Eric's rehearsed line,
     * `holder-prep --dir D --name N --mldsa-seed S [--keygen] --backup-mldsa-seed B
     * [--backup-keygen]` (both slots at the tool's default, 9c; `--intermediate P` only
     * when given, after `--keygen`).
     */
    fun holderPrep(p: HolderPrep): List<String> = buildList {
        add("holder-prep")
        add("--dir"); add(p.dir)
        add("--name"); add(p.name)
        add("--mldsa-seed"); add(p.mldsaSeed)
        if (p.keygen) add("--keygen")
        p.intermediates.filter { it.isNotBlank() }.forEach { add("--intermediate"); add(it) }
        add("--backup-mldsa-seed"); add(p.backupMldsaSeed)
        if (p.backupKeygen) add("--backup-keygen")
    }

    /** Step 2, each seat operator: `seat --dir D --name N --seat-seed S --transport T --dial H...`. */
    fun seat(dir: String, name: String, seatSeed: String, transport: String, dial: List<String>): List<String> =
        buildList {
            add("seat")
            add("--dir"); add(dir)
            add("--name"); add(name)
            add("--seat-seed"); add(seatSeed)
            add("--transport"); add(transport)
            dial.filter { it.isNotBlank() }.forEach { add("--dial"); add(it.trim()) }
        }

    /** The coordinator fixes the party set: `terms --dir D`. */
    fun terms(dir: String): List<String> = listOf("terms", "--dir", dir)

    /**
     * Steps 1b, 4 and 3, each holder in ONE sitting: one PIN, three touches. The halt goes to
     * the holder's own sealed directory, never into D. `--module` only when someone filled it in.
     */
    fun holderSign(dir: String, name: String, mldsaSeed: String, sealedDir: String, module: String? = null): List<String> =
        buildList {
            add("holder-sign")
            add("--dir"); add(dir)
            add("--name"); add(name)
            add("--mldsa-seed"); add(mldsaSeed)
            add("--sealed-dir"); add(sealedDir)
            module?.takeIf { it.isNotBlank() }?.let { add("--module"); add(it.trim()) }
        }

    /** Every holder's halt before publish: `check --dir D --halts H1 --halts H2 ...`. */
    fun check(dir: String, halts: List<String>): List<String> = buildList {
        add("check"); add("--dir"); add(dir)
        halts.filter { it.isNotBlank() }.forEach { add("--halts"); add(it) }
    }

    /** Step 5's gate: the real import door, then the model check. */
    fun finalGate(dir: String, halts: List<String>): List<String> = buildList {
        add("final-gate"); add("--dir"); add(dir)
        halts.filter { it.isNotBlank() }.forEach { add("--halts"); add(it) }
    }

    /** `--help` and `--version`: read-only, run without a terminal. */
    fun help(): List<String> = listOf("--help")
    fun version(): List<String> = listOf("--version")

    /** The subcommands [helpText] does not list (empty when the tool offers every one). */
    fun missingSubcommands(helpText: String): List<String> = SUBCOMMANDS.filter { cmd ->
        !Regex("""(?m)^\s+${Regex.escape(cmd)}\s""").containsMatchIn(helpText)
    }

    /** The command as one line a person could paste into a terminal (single-quoted where needed). */
    fun shellLine(args: List<String>, tool: String = "genesis-ceremony"): String =
        (listOf(tool) + args).joinToString(" ") { shellQuote(it) }

    fun shellQuote(arg: String): String =
        if (arg.isNotEmpty() && arg.all { it.isLetterOrDigit() || it in "-_./:=@+," }) arg
        else "'" + arg.replace("'", "'\\''") + "'"
}
