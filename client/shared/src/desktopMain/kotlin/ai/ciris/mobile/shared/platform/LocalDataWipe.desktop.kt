package ai.ciris.mobile.shared.platform

import java.io.File
import java.nio.file.Files
import java.util.prefs.Preferences

/**
 * Generated state a node writes into its home. Everything here is recreated on
 * the next boot; nothing here is authored by a human.
 *
 * Deliberately an ALLOW-LIST. The cost of missing an entry is a stale file; the
 * cost of guessing wrong is someone else's data.
 */
private val GENERATED_STATE = listOf(
    ".env",
    "ceg",
    "claim_pin",
    "config",
    "data",
    "data_archive",
    "identity",
    "keys",
    "logs",
    "secrets",
    "startup_python_hashes.json",
)

/**
 * Entries that are TRACKED SOURCE in a checkout and must not be deleted there.
 *
 * `config/` is generated state in a dedicated home and repository source in a
 * checkout — it holds `config/essential.yaml` and
 * `config/environment_variables.md`. One name, two meanings, decided by where
 * the home happens to be.
 */
private val TRACKED_IN_CHECKOUT = setOf("config")

/**
 * The node home, resolved the way the BACKEND resolves it.
 *
 * Mirrors `ciris_engine.logic.utils.path_resolution.get_ciris_home()`:
 *   /app (managed) -> $CIRIS_HOME -> the dev checkout -> ~/ciris (installed)
 */
private fun resolveNodeHome(declared: String?): File? {
    // WHAT THE NODE SAID BEATS WHAT WE CAN INFER. Everything below reconstructs
    // the backend's get_ciris_home() from THIS process's environment, which is a
    // different process whenever the client talks to a node over CIRIS_API_URL
    // or a launcher started it with its own home. In those topologies the guess
    // names an unrelated installation, and the wipe would erase that while
    // leaving the live node untouched.
    declared?.takeIf { it.isNotBlank() }?.let { return File(it) }

    if (isManagedDeployment()) return File("/app")
    System.getenv("CIRIS_HOME")?.takeIf { it.isNotBlank() }?.let { return File(it) }

    var dir: File? = File(System.getProperty("user.dir", "."))
    repeat(5) {
        val d = dir ?: return@repeat
        if (File(d, "main.py").exists() && File(d, "ciris_engine").isDirectory) return d
        dir = d.parentFile
    }

    return System.getProperty("user.home")?.let { File(it, "ciris") }
}

/** A source checkout, where `config/` is tracked source rather than node state. */
private fun isSourceCheckout(home: File): Boolean =
    File(home, ".git").exists() ||
        (File(home, "main.py").exists() && File(home, "ciris_engine").isDirectory)

/**
 * Erase local node state.
 *
 * ONE PATH: the home directory itself is NEVER deleted, anywhere.
 *
 * This function had two modes — dedicated homes removed whole, checkouts wiped
 * entry by entry — and that distinction was wrong three revisions running. Each
 * time, a guard was added to make whole-directory deletion safe, and each time
 * the guard admitted something it should not have:
 *
 *   1. resolved `~/ciris` while the backend's home was a source checkout, so it
 *      deleted an unrelated installed agent and left the live node configured;
 *   2. resolved the checkout correctly and then recursively deleted it — the
 *      whole repository, tracked source and uncommitted work, because a
 *      configured checkout has `.env` and `data/` and the marker check passed;
 *   3. treated a bare `data/` or `logs/` as proof of ownership, so a
 *      `CIRIS_HOME` pointed at `$HOME` or any shared directory would take
 *      everything in it with it.
 *
 * The mode bought nothing that justified that. Removing only what the node
 * generates reaches the identical end state — no `.env`, so the next boot is a
 * genuine first run — and leaves no input, resolved or misresolved, that can
 * make this delete something it does not recognise. What survives is an empty
 * directory and any file we never wrote, which is exactly what should survive.
 */
actual fun wipeLocalData(declaredNodeHome: String?): Boolean {
    // Managed deployments are not ours to wipe: `/app` belongs to CIRIS-Manager,
    // and the person at this UI is not necessarily entitled to destroy it.
    if (isManagedDeployment()) {
        println("[LocalDataWipe] refusing: CIRIS-Manager-managed deployment (/app)")
        return false
    }

    val home = resolveNodeHome(declaredNodeHome) ?: return false
    if (!home.exists()) return true

    val checkout = isSourceCheckout(home)
    var ok = true
    var removed = 0

    for (name in GENERATED_STATE) {
        if (checkout && name in TRACKED_IN_CHECKOUT) {
            println("[LocalDataWipe] skipping $name — tracked source in a checkout")
            continue
        }
        val f = File(home, name)
        if (!f.exists()) continue

        // A SYMLINK IS NOT THE DIRECTORY IT POINTS AT (Codex, PR #9).
        //
        // `File.isDirectory` follows links, so a node whose data/ was relocated
        // onto a bigger volume — `CIRIS_HOME/data -> /mnt/store/ciris` — would
        // have `deleteRecursively()` walk into the TARGET and erase it. The
        // allow-list bounds which NAMES may be removed; it says nothing about
        // where those names lead, and that is the same "this resolved, therefore
        // it is mine" premise the whole-directory delete was abandoned for.
        //
        // Remove the link itself, never what it references. The node recreates
        // the entry on next boot; the volume behind it was never node state.
        if (Files.isSymbolicLink(f.toPath())) {
            runCatching { Files.delete(f.toPath()) }
            if (f.exists()) {
                println("[LocalDataWipe] could not remove symlink ${f.absolutePath}")
                ok = false
            } else {
                println("[LocalDataWipe] removed symlink $name (its target was left alone)")
                removed++
            }
            continue
        }
        runCatching { if (f.isDirectory) f.deleteRecursively() else f.delete() }
        if (f.exists()) {
            println("[LocalDataWipe] could not remove ${f.absolutePath}")
            ok = false
        } else {
            removed++
        }
    }

    // THE FILESYSTEM IS NOT ALL THE LOCAL DATA (Codex, PR #9).
    //
    // SecureStorage.desktop keeps provider API keys in java.util.prefs under
    // `apikey_*`, and `getApiKey()` falls back to them once .env is gone.
    // logout() clears tokens and user fields and leaves those, so a wipe that
    // stopped at the filesystem handed the next owner of the machine the
    // previous owner's provider key — under a dialog that says "erase all local
    // data". Removing files is the visible half of the promise; this is the half
    // that decides whether the promise was true.
    if (!clearSecurePreferences()) ok = false

    println("[LocalDataWipe] ${home.absolutePath}: removed $removed generated entries, ok=$ok")
    return ok
}

/**
 * Clear the node between the filesystem and the keyring: `java.util.prefs`.
 *
 * The whole subtree is removed rather than an enumerated key list — an
 * allow-list is right for a filesystem shared with a user's own files, and
 * wrong here, where the node owns the node. A key added later that nobody
 * remembered to enumerate is exactly how a credential outlives a reset.
 */
private fun clearSecurePreferences(): Boolean = runCatching {
    // The SAME accessor SecureStorage.desktop uses, not a path that looks like
    // it. `userNodeForPackage(SecureStorage::class.java)` resolves from the
    // class's package; a hand-written node name would have cleared an empty
    // node and reported success — a silent no-op wearing the shape of a fix,
    // which is the defect class this whole review is about.
    val node = Preferences.userNodeForPackage(SecureStorage::class.java)
    val keys = node.keys().size
    node.removeNode()
    node.flush()
    println("[LocalDataWipe] cleared $keys secure preference(s)")
    true
}.getOrElse { e ->
    println("[LocalDataWipe] could not clear secure preferences: ${e.message}")
    false
}
