package ai.ciris.mobile.shared.platform

import java.io.File
import java.io.RandomAccessFile

actual fun agentLogTails(maxBytesEach: Int): List<Pair<String, String>> {
    val home = runCatching { EnvFileUpdater.getCirisHome() }.getOrNull()
        ?: return listOf("agent logs" to "(CIRIS_HOME is not known to this process)")
    val dir = File(home, "logs")
    return AGENT_LOG_FILES.map { name ->
        val f = File(dir, name)
        name to runCatching {
            if (!f.isFile) "(not present in ${dir.path})"
            else RandomAccessFile(f, "r").use { raf ->
                val len = raf.length()
                val start = (len - maxBytesEach).coerceAtLeast(0)
                val buf = ByteArray((len - start).toInt())
                raf.seek(start)
                raf.readFully(buf)
                tailText(buf, maxBytesEach, earlierCut = start > 0)
            }
        }.getOrElse { "(could not read: ${it::class.simpleName}: ${it.message})" }
    }
}
