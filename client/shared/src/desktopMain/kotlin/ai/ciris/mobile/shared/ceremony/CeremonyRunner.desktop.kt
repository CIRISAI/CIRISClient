package ai.ciris.mobile.shared.ceremony

import ai.ciris.mobile.shared.platform.PlatformLogger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

actual fun platformCeremonyRunner(): CeremonyRunner? = DesktopCeremonyRunner()

/**
 * THE BUNDLED TOOL, VERIFIED BEFORE EVERY RUN (CSD-069). The desktop build carries the
 * `genesis-ceremony` binary only when it was built with -PceremonyBinary, which a release
 * build never sets, so no wheel and no published jar carries it. Before each run the
 * resource is copied to a private runtime directory (mode 0700), and the copy on disk is
 * hashed and compared with the sha256 baked in at build time. A mismatch refuses by name.
 * Only that copy is run: never ~/.cache, never $PATH.
 */
object CeremonyToolBundle {
    const val FILE_NAME = "genesis-ceremony"

    fun sha256(file: File): String = file.inputStream().use { input ->
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    /** XDG_RUNTIME_DIR when set (per-user, private), else ~/.cache/ciris-client; then ceremony/. */
    fun runtimeDir(): File {
        val base = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }
            ?: File(System.getProperty("user.home") ?: "/tmp", ".cache/ciris-client")
        return File(base, "ciris-ceremony").apply { mkdirs(); privateDir(this) }
    }

    sealed interface Verified {
        data class Ok(val file: File) : Verified
        data class Mismatch(val expected: String, val actual: String) : Verified
        data class Missing(val detail: String) : Verified
    }

    /**
     * Copy [source] into [dir] as the runnable tool and verify the COPY against [expectedSha].
     * On a mismatch the copy is deleted and nothing is left to run.
     */
    fun verifiedCopy(source: InputStream, expectedSha: String, dir: File, fileName: String = FILE_NAME, executable: Boolean = true): Verified {
        if (expectedSha.isBlank()) return Verified.Missing("no sha256 was built in")
        dir.mkdirs()
        val tmp = File.createTempFile("$fileName-", ".part", dir)
        try {
            source.use { s -> tmp.outputStream().use { s.copyTo(it) } }
            ownerOnly(tmp, executable = executable)
            val actual = sha256(tmp)
            if (!actual.equals(expectedSha, ignoreCase = true)) {
                tmp.delete()
                return Verified.Mismatch(expectedSha, actual)
            }
            val target = File(dir, fileName)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            ownerOnly(target, executable = executable)
            return Verified.Ok(target)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** The bundled resource, copied and verified now. */
    fun extract(): Verified {
        if (CeremonyToolBuild.SHA256.isEmpty()) return Verified.Missing("this build has no ceremony tool")
        val stream = CeremonyToolBundle::class.java.classLoader?.getResourceAsStream(CeremonyToolBuild.RESOURCE)
            ?: return Verified.Missing("the build names a tool but carries no ${CeremonyToolBuild.RESOURCE}")
        return verifiedCopy(stream, CeremonyToolBuild.SHA256, runtimeDir())
    }

    /** How many Yubico intermediates a holder-prep needs: PIV Attestation B 1 and Attestation Intermediate B 1. */
    const val INTERMEDIATES_NEEDED = 2

    /**
     * The intermediates in [entries] (resource to sha256, chain order), each copied beside the
     * tool and verified against its built-in hash now. Missing if the build carries fewer than
     * two; Refused on any mismatch, naming which.
     */
    fun intermediates(entries: List<Pair<String, String>>, open: (String) -> InputStream?, dir: File): CeremonyIntermediates {
        if (entries.size != INTERMEDIATES_NEEDED) {
            return CeremonyIntermediates.Missing(
                "this build carries ${entries.size} of the $INTERMEDIATES_NEEDED Yubico intermediates " +
                    "(Yubico PIV Attestation B 1, Yubico Attestation Intermediate B 1); build it with -PceremonyIntermediates",
            )
        }
        val paths = entries.map { (resource, sha) ->
            val stream = open(resource) ?: return CeremonyIntermediates.Missing("the build names $resource but does not carry it")
            when (val v = verifiedCopy(stream, sha, dir, resource.substringAfterLast('/'), executable = false)) {
                is Verified.Ok -> v.file.absolutePath
                is Verified.Mismatch -> return CeremonyIntermediates.Refused("$resource: sha256 ${v.actual}, not the built ${v.expected}")
                is Verified.Missing -> return CeremonyIntermediates.Missing("$resource: ${v.detail}")
            }
        }
        return CeremonyIntermediates.Ready(paths)
    }

    fun bundledIntermediates(): CeremonyIntermediates = intermediates(
        CeremonyToolBuild.INTERMEDIATES,
        { CeremonyToolBundle::class.java.classLoader?.getResourceAsStream(it) },
        runtimeDir(),
    )

    private fun ownerOnly(f: File, executable: Boolean) {
        runCatching { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString(if (executable) "rwx------" else "rw-------")) }
    }

    private fun privateDir(d: File) {
        runCatching { Files.setPosixFilePermissions(d.toPath(), PosixFilePermissions.fromString("rwx------")) }
    }
}

/**
 * The tool under a pseudo-terminal, so its PIN prompt reads from a real terminal with
 * echo off (rpassword), exactly as when Eric rehearses it from a shell. util-linux
 * `script -qefc <command> /dev/null` provides the terminal: what this process writes
 * reaches the tool's terminal, and everything the tool prints comes back. BSD/macOS
 * `script` takes different arguments and Windows has none, so this runner refuses there.
 */
class DesktopCeremonyRunner(
    private val tool: () -> CeremonyToolBundle.Verified = CeremonyToolBundle::extract,
    private val toolInfo: () -> Pair<String, Long> = { CeremonyToolBuild.COMMIT to CeremonyToolBuild.SIZE_BYTES },
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val intermediates: () -> CeremonyIntermediates = CeremonyToolBundle::bundledIntermediates,
) : CeremonyRunner {
    override fun custodyIntermediates(): CeremonyIntermediates = intermediates()
    override val fs: CeremonyFs = JvmCeremonyFs
    override val bundled: Boolean get() = CeremonyToolBuild.SHA256.isNotEmpty()

    private fun unsupported(): String? = when {
        osName.startsWith("Linux", ignoreCase = true) -> null
        osName.startsWith("Mac", ignoreCase = true) -> "macOS: the terminal wrapper for the PIN prompt is not built for macOS yet"
        else -> "$osName: there is no terminal wrapper for the PIN prompt here"
    }

    override suspend fun prepare(): CeremonyToolState {
        unsupported()?.let { return CeremonyToolState.Unsupported(it) }
        if (CeremonyToolBuild.SHA256.isEmpty()) return CeremonyToolState.NotBundled
        val file = when (val v = tool()) {
            is CeremonyToolBundle.Verified.Ok -> v.file
            is CeremonyToolBundle.Verified.Mismatch -> return CeremonyToolState.Refused(
                "mobile.genesis_tool_hash_mismatch", "expected ${v.expected}, the copy is ${v.actual}",
            )
            is CeremonyToolBundle.Verified.Missing -> return if (CeremonyToolBuild.SHA256.isEmpty()) CeremonyToolState.NotBundled
            else CeremonyToolState.Refused("mobile.genesis_tool_missing", v.detail)
        }
        val help = plain(file, CeremonyCommands.help()) ?: return CeremonyToolState.Refused("mobile.genesis_tool_no_help", "--help did not answer")
        val missing = CeremonyCommands.missingSubcommands(help)
        if (missing.isNotEmpty()) return CeremonyToolState.Refused("mobile.genesis_tool_missing_subcommand", missing.joinToString(", "))
        val version = plain(file, CeremonyCommands.version())?.trim()?.lines()?.firstOrNull()?.takeIf { it.isNotBlank() }
        val (commit, size) = toolInfo()
        return CeremonyToolState.Ready(CeremonyToolInfo(version, commit, CeremonyToolBundle.sha256(file), size))
    }

    /** A read-only command without a terminal (--help, --version): its output, or null when it failed. */
    private fun plain(file: File, args: List<String>): String? = runCatching {
        val p = ProcessBuilder(listOf(file.absolutePath) + args).redirectErrorStream(true).start()
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        if (p.exitValue() == 0) out else null
    }.getOrNull()

    override fun run(step: CeremonyStep, args: List<String>, externalSigner: CeremonyExternalSigner?): CeremonyRun {
        unsupported()?.let { return FailedRun(it) }
        val file = when (val v = tool()) { // verified before EVERY run
            is CeremonyToolBundle.Verified.Ok -> v.file
            is CeremonyToolBundle.Verified.Mismatch -> return FailedRun("refused: the ceremony tool's sha256 is ${v.actual}, not the built ${v.expected}")
            is CeremonyToolBundle.Verified.Missing -> return FailedRun("refused: ${v.detail}")
        }
        val command = (listOf(file.absolutePath) + args).joinToString(" ") { CeremonyCommands.shellQuote(it) }
        PlatformLogger.i(TAG, "[run] ${step.subcommand}: ${CeremonyCommands.shellLine(args)}")
        return PtyRun(scriptArgv(command))
    }

    companion object {
        private const val TAG = "GenesisCeremony"

        /** util-linux: quiet, the child's exit code, flush, run [command]; no typescript file. */
        fun scriptArgv(command: String): List<String> = listOf("script", "-qefc", command, "/dev/null")
    }
}

private class FailedRun(message: String) : CeremonyRun {
    private val ch = Channel<CeremonyEvent>(Channel.UNLIMITED).apply {
        trySend(CeremonyEvent.Output("$message\n"))
        trySend(CeremonyEvent.Exited(126))
        close()
    }
    override val events: Flow<CeremonyEvent> = ch.receiveAsFlow()
    override fun answer(answer: CeremonyAnswer) = false
    override fun pin(pin: CharArray): Boolean { pin.fill('\u0000'); return false }
    override fun cancel() = Unit
}

private const val PIN_SETTLE_MS = 300L

/** One step under `script`. What is written goes to the tool's terminal; what it prints comes back. */
internal class PtyRun(argv: List<String>) : CeremonyRun {
    private val ch = Channel<CeremonyEvent>(Channel.UNLIMITED)
    override val events: Flow<CeremonyEvent> = ch.receiveAsFlow()

    private val process: Process = ProcessBuilder(argv)
        .redirectErrorStream(true)
        .also { pb ->
            // The command runs through $SHELL -c: a plain POSIX shell, and a terminal with no colours.
            pb.environment()["SHELL"] = "/bin/sh"
            pb.environment()["TERM"] = "dumb"
        }
        .start()
    private val input: OutputStream = process.outputStream
    private val lock = Any()
    @Volatile private var tail = ""
    @Volatile private var pending: CeremonyOutput.Prompt? = null
    @Volatile private var lastTouch: CeremonyOutput.Touch? = null
    @Volatile private var promptAtMs = 0L

    init {
        Thread({ pump(process.inputStream) }, "genesis-ceremony-pty").apply { isDaemon = true }.start()
    }

    private fun pump(out: InputStream) {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        val bytes = ByteArray(4096)
        val inBuf = ByteBuffer.allocate(8192)
        val chars = CharBuffer.allocate(8192)
        try {
            while (true) {
                val n = out.read(bytes)
                if (n < 0) break
                inBuf.put(bytes, 0, n).flip()
                decoder.decode(inBuf, chars, false)
                inBuf.compact()
                chars.flip()
                val text = CeremonyOutput.clean(chars.toString())
                chars.clear()
                if (text.isNotEmpty()) emit(text)
            }
        } catch (_: Exception) {
            // The process ended or was cancelled.
        }
        val code = runCatching { process.waitFor() }.getOrDefault(-1)
        ch.trySend(CeremonyEvent.Exited(code))
        ch.close()
    }

    private fun emit(text: String) {
        ch.trySend(CeremonyEvent.Output(text))
        tail = (tail + text).takeLast(4096)
        val prompt = CeremonyOutput.pendingPrompt(tail)
        if (prompt != null && prompt != pending) {
            promptAtMs = System.currentTimeMillis()
            ch.trySend(CeremonyEvent.Prompt(prompt))
        }
        pending = prompt
        CeremonyOutput.lastTouch(tail)?.let { t ->
            if (t != lastTouch) { lastTouch = t; ch.trySend(CeremonyEvent.Touch(t.n, t.of)) }
        }
    }

    override fun answer(answer: CeremonyAnswer): Boolean = synchronized(lock) {
        val ok = when (answer) {
            CeremonyAnswer.YES -> pending == CeremonyOutput.Prompt.CONFIRM
            CeremonyAnswer.SWAPPED -> pending == CeremonyOutput.Prompt.SWAP_TOKEN
        }
        if (!ok) return false
        val line = if (answer == CeremonyAnswer.YES) "yes\n" else "\n"
        runCatching { input.write(line.toByteArray()); input.flush() }.isSuccess.also { if (it) pending = null }
    }

    override fun pin(pin: CharArray): Boolean = synchronized(lock) {
        try {
            if (pending != CeremonyOutput.Prompt.PIN) return false
            // The tool prints its prompt and THEN turns the terminal's echo off: give it a
            // moment, so a PIN sent at once (by automation) is never echoed back.
            val wait = PIN_SETTLE_MS - (System.currentTimeMillis() - promptAtMs)
            if (wait > 0) Thread.sleep(wait)
            val buf = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(pin))
            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            try {
                input.write(bytes); input.write('\n'.code); input.flush()
            } finally {
                bytes.fill(0)
                if (buf.hasArray()) buf.array().fill(0)
            }
            pending = null
            true
        } catch (_: Exception) {
            false
        } finally {
            pin.fill('\u0000')
        }
    }

    override fun cancel() {
        process.descendants().forEach { it.destroy() }
        process.destroy()
    }
}

/** The ceremony screen's file reads, over java.io. */
object JvmCeremonyFs : CeremonyFs {
    override fun exists(path: String) = File(path).exists()
    override fun isDirectory(path: String) = File(path).isDirectory
    override fun readText(path: String): String? = File(path).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
    override fun list(path: String): List<String> = File(path).list()?.toList().orEmpty()
    override fun parent(path: String): String? = File(path).absoluteFile.parent
    override fun join(dir: String, child: String): String = File(dir, child).path
    override fun isWithin(path: String, dir: String): Boolean = runCatching {
        val p = File(path).canonicalFile.toPath()
        val d = File(dir).canonicalFile.toPath()
        p.startsWith(d)
    }.getOrDefault(false)
    override fun walk(dir: String): List<String> {
        val root = File(dir)
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.sorted().toList()
    }
    override fun size(path: String): Long = File(path).length()
    override fun sha256(path: String): String? = File(path).takeIf { it.isFile }?.let { runCatching { CeremonyToolBundle.sha256(it) }.getOrNull() }
    override fun appendLine(path: String, line: String) {
        java.io.FileOutputStream(File(path), true).use { it.write((line + "\n").toByteArray()) }
    }
}
