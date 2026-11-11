package ai.ciris.mobile.shared.calls.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * One library Cisco publishes: its file name on Cisco's server (with `.bz2`
 * added) and the SHA-256 Cisco publishes for the library itself, uncompressed.
 */
internal class OpenH264Build(val fileName: String, val sha256: String)

/**
 * OpenH264 2.7.0 as Cisco publishes it. The hashes are Cisco's own, from
 * `https://ciscobinary.openh264.org/<file>.signed.sha256.txt`. A new version is
 * a new table here and a new look at the struct layouts in `OpenH264Api`, so
 * nothing downloads "the latest".
 */
internal object OpenH264Builds {
    const val VERSION = "2.7.0"
    const val BASE_URL = "https://ciscobinary.openh264.org"

    private val linuxX64 = OpenH264Build("libopenh264-2.7.0-linux64.8.so", "1522f873a482923c5ec018a4415e1866898197528828c50f90fd300c94620df3")
    private val linuxArm64 = OpenH264Build("libopenh264-2.7.0-linux-arm64.8.so", "9ac664b0154fd0868491c2a904397b8a05779874c0603b8b1f16ad15de1ffd82")
    private val macX64 = OpenH264Build("libopenh264-2.7.0-mac-x64.dylib", "31d38bcbe9348e9b44ca38dd08fa1e2af79ffce6521276449ef05e3cd39136f4")
    private val macArm64 = OpenH264Build("libopenh264-2.7.0-mac-arm64.dylib", "2e1ae3cd10c08328ca03a1f304fd2a1a2a7e5658c6d5a8582101a963f7995ef0")
    private val winX64 = OpenH264Build("openh264-2.7.0-win64.dll", "0ef5e7f3b38753b15c13afa0418d4d7805fa228e6adfd3e425bbf758cff1c288")
    private val winArm64 = OpenH264Build("openh264-2.7.0-win-arm64.dll", "2f8f11a40d1450fd5c7afc1c5e38b9f6ba6c95c47582845b16c0bfe154ed75a4")

    /** The build for this OS and processor, or null where Cisco publishes none this app can call (32-bit, other systems). */
    fun forMachine(
        osName: String = System.getProperty("os.name").orEmpty(),
        arch: String = System.getProperty("os.arch").orEmpty(),
    ): OpenH264Build? {
        val os = osName.lowercase()
        val x64 = arch == "amd64" || arch == "x86_64"
        val arm64 = arch == "aarch64" || arch == "arm64"
        return when {
            os.contains("linux") -> if (x64) linuxX64 else if (arm64) linuxArm64 else null
            os.contains("mac") || os.contains("darwin") -> if (x64) macX64 else if (arm64) macArm64 else null
            os.contains("win") -> if (x64) winX64 else if (arm64) winArm64 else null
            else -> null
        }
    }

    /**
     * Where the library is kept: this person's own data directory, not a node's
     * home. It is a public file and the same for every node this person runs.
     */
    fun directory(
        osName: String = System.getProperty("os.name").orEmpty(),
        env: (String) -> String? = System::getenv,
        home: String = System.getProperty("user.home").orEmpty(),
    ): File {
        val os = osName.lowercase()
        val root = when {
            os.contains("win") -> env("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home, "AppData/Local")
            os.contains("mac") || os.contains("darwin") -> File(home, "Library/Application Support")
            else -> env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home, ".local/share")
        }
        return File(root, "ciris-client/openh264")
    }
}

/** What arrived is not the library Cisco published. */
internal class OpenH264CheckFailed(message: String) : Exception(message)

/**
 * The library on disk. A file is here only if it is, byte for byte, the one
 * Cisco published: it is checked before it is written, and again each time the
 * app starts, and a file that fails is deleted.
 */
internal class OpenH264Store(
    private val dir: File,
    val build: OpenH264Build,
    private val baseUrl: String = OpenH264Builds.BASE_URL,
) {
    val file: File get() = File(dir, build.fileName)

    /**
     * Written when the library could not be deleted because it is loaded (Windows
     * holds a loaded DLL). The person turned it off, so it is not used, and the
     * next start deletes both.
     */
    private val off: File get() = File(dir, build.fileName + ".off")

    /** The library, if it is here, was not turned off, and is the published one. */
    fun verified(): File? {
        val f = file
        if (off.exists()) {
            if (f.delete() || !f.exists()) off.delete()
            return null
        }
        if (!f.isFile) return null
        if (f.length() > MAX_LIBRARY || sha256(f.readBytes()) != build.sha256) {
            f.delete()
            return null
        }
        return f
    }

    /**
     * Fetch `<file>.bz2` from Cisco, unpack it, check it, keep it.
     * [IOException] when it could not be fetched; [OpenH264CheckFailed] when what
     * came is not the published library. Either way nothing is kept.
     */
    fun download(): File {
        val url = URI("$baseUrl/${build.fileName}.bz2").toURL()
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        // Nothing about this person or this app goes with the request: no cookies, and a
        // generic client name rather than Java's, which carries the runtime's version.
        conn.setRequestProperty("User-Agent", "ciris-client")
        val packed = try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} from ${url.host}")
            conn.inputStream.use { readAtMost(it, MAX_DOWNLOAD) }
        } finally {
            conn.disconnect()
        }
        return install(packed)
    }

    /** [packed] is the `.bz2` as fetched. Kept only if it unpacks to the published library. */
    fun install(packed: ByteArray): File {
        val library = try {
            Bzip2.decompress(packed, MAX_LIBRARY)
        } catch (e: Bzip2.Malformed) {
            throw OpenH264CheckFailed("not a bzip2 file Cisco would publish: ${e.message}")
        }
        val got = sha256(library)
        if (got != build.sha256) throw OpenH264CheckFailed("sha256 $got is not ${build.sha256}")
        dir.mkdirs()
        // Written beside the name and moved onto it, so a file with the library's name is always whole.
        val tmp = File(dir, build.fileName + ".part")
        tmp.writeBytes(library)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        off.delete()
        return file
    }

    /** Delete the library. Where the system holds it open, leave the mark that it is off. */
    fun remove() {
        val f = file
        if (!f.exists()) return
        if (!f.delete()) {
            dir.mkdirs()
            off.writeText("turned off; delete on next start\n")
        }
    }

    private fun readAtMost(input: java.io.InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > max) throw OpenH264CheckFailed("more than $max bytes: not the published file")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        /** The largest published `.bz2` is 0.6 MiB and the largest library 1.7 MiB. */
        const val MAX_DOWNLOAD = 4 shl 20
        const val MAX_LIBRARY = 16 shl 20

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/**
 * The person's switch for H.264 on this desktop, and the loaded library behind it.
 * [api] is non-null exactly while [state] is ON.
 */
internal class DesktopH264(
    private val store: OpenH264Store,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val load: (File) -> OpenH264Api = OpenH264Api::load,
) : H264Download {
    private val _state = MutableStateFlow(H264Download.State.OFF)
    override val state: StateFlow<H264Download.State> = _state.asStateFlow()

    @Volatile
    var api: OpenH264Api? = null
        private set

    override val licence: String by lazy {
        checkNotNull(DesktopH264::class.java.getResource(LICENCE_RESOURCE)) { "$LICENCE_RESOURCE is not in this build" }.readText()
    }

    init {
        // Turned on in an earlier run: it is on now, with no request to anyone.
        store.verified()?.let { use(it) }
    }

    @Synchronized
    override fun turnOn() {
        if (_state.value == H264Download.State.DOWNLOADING || _state.value == H264Download.State.ON) return
        _state.value = H264Download.State.DOWNLOADING
        scope.launch { fetchAndUse() }
    }

    /** What [turnOn] starts, as one call, for a test to wait on. */
    internal fun fetchAndUse() {
        val file = try {
            store.verified() ?: store.download()
        } catch (e: OpenH264CheckFailed) {
            System.err.println("[call] OpenH264 was not kept: ${e.message}")
            _state.value = H264Download.State.FAILED_CHECK
            return
        } catch (e: IOException) {
            System.err.println("[call] OpenH264 was not downloaded: $e")
            _state.value = H264Download.State.FAILED_DOWNLOAD
            return
        }
        use(file)
    }

    private fun use(file: File) {
        try {
            api = load(file)
            _state.value = H264Download.State.ON
        } catch (t: Throwable) { // UnsatisfiedLinkError, a version that is not the pinned one, no JNA for this OS
            System.err.println("[call] OpenH264 would not load: $t")
            api = null
            _state.value = H264Download.State.FAILED_LOAD
        }
    }

    @Synchronized
    override fun turnOff() {
        // The library stays mapped until the app exits: an encoder or decoder on another
        // thread may be inside it at this moment, so it is not unloaded under them. It is
        // not called again: every encoder and decoder asks `enabled` before each call.
        api?.enabled = false
        api = null
        store.remove()
        _state.value = H264Download.State.OFF
    }

    companion object {
        const val LICENCE_RESOURCE = "/licences/openh264-binary-licence.txt"
    }
}

/** This process's one switch. A test puts its own here, on a directory of its own. */
internal object DesktopH264Holder {
    @Volatile
    var current: DesktopH264? = null

    private val default: DesktopH264? by lazy {
        OpenH264Builds.forMachine()?.let { DesktopH264(OpenH264Store(OpenH264Builds.directory(), it)) }
    }

    fun get(): DesktopH264? = current ?: default
}

actual fun h264Download(): H264Download? = DesktopH264Holder.get()
