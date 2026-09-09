package ai.ciris.desktop

import ai.ciris.desktop.testing.TestAutomationServer
import ai.ciris.mobile.shared.CIRISApp
import ai.ciris.mobile.shared.localization.LocalizationResourceLoader
import ai.ciris.mobile.shared.platform.TestAutomation
import ai.ciris.mobile.shared.platform.createEnvFileUpdater
import ai.ciris.mobile.shared.platform.createPythonRuntime
import ai.ciris.mobile.shared.platform.createSecureStorage
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.res.painterResource
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.File

fun main() {
    // Set macOS application name (menu bar + dock)
    System.setProperty("apple.awt.application.name", "CIRIS Agent")

    // Robust crash guards — we have zero ANRs in prod on both app stores
    // and desktop should match. A single unhandled exception in a
    // coroutine / Compose callback / AWT event should NEVER wedge the UI
    // or kill the JVM silently.
    //
    // 1) JVM-wide fallback: anything that escapes every catch block
    //    ends up here. Log it; do not let the thread die quietly.
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        System.err.println(
            "[DesktopCrashGuard] UNCAUGHT on ${thread.name}: ${error::class.qualifiedName}: ${error.message}"
        )
        error.printStackTrace(System.err)
    }
    // 2) AWT EDT-specific handler (Swing dispatches exceptions through
    //    sun.awt.exception.handler when set as a system property). If the
    //    EDT hits a fatal error we still want the app to keep running.
    System.setProperty(
        "sun.awt.exception.handler",
        "ai.ciris.desktop.AwtExceptionHandler"
    )

    // Set macOS Dock icon. painterResource("icon.png") on the Window
    // only controls the title-bar icon — the Dock, Cmd-Tab switcher,
    // and app-bundle representation need the JVM's AWT Taskbar API.
    // Without this, raw `java -jar …` launches show the default Java
    // coffee-cup icon in the Dock. Works on macOS Big Sur+.
    runCatching {
        val iconStream = object {}.javaClass.classLoader.getResourceAsStream("icon.png")
        if (iconStream != null) {
            val image = javax.imageio.ImageIO.read(iconStream)
            if (image != null && java.awt.Taskbar.isTaskbarSupported()) {
                val taskbar = java.awt.Taskbar.getTaskbar()
                if (taskbar.isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) {
                    taskbar.iconImage = image
                }
            }
        }
    }.onFailure { e ->
        println("[Desktop] Could not set Dock icon: ${e.message}")
    }

    // Initialize localization directory for development
    // Try to find the localization directory relative to the project root
    val localizationPaths = listOf(
        File("localization"),                                      // Current dir
        File("../localization"),                                   // Parent
        File("../../localization"),                                // Grandparent (from mobile)
        File("../../../localization"),                             // From mobile/desktopApp
        File(System.getProperty("user.dir"), "localization"),      // Working dir
        File(System.getProperty("user.home"), "CIRISAgent/localization"),  // Home
    )
    for (path in localizationPaths) {
        if (path.exists() && path.isDirectory) {
            println("[Desktop] Found localization directory: ${path.absolutePath}")
            LocalizationResourceLoader.init(path)
            break
        }
    }

    // Create the runtime early so we can shut it down on exit
    val pythonRuntime = createPythonRuntime()

    // Register JVM shutdown hook to kill server process if we launched one
    Runtime.getRuntime().addShutdownHook(Thread {
        pythonRuntime.shutdown()
    })

    // WATCH THE NODE. Desktop spawns a real child process and so can answer
    // "is it alive?" exactly, via Process.isAlive — and never asked. Nothing
    // monitored it: if the node died, the first anyone knew was a failing
    // request, and the only recovery was a button a user had to find. Laptop
    // sleep is the phones' failure under a different name.
    //
    // Same supervisor, same policy, same tests as Android and iOS. Only a
    // loopback node is ever restarted; pointed at someone else's node this
    // observes and reports.
    // CIRIS_NODE_URL NAMES THE NODE. CIRIS_API_URL NAMES THE AGENT, AND THE
    // FALLBACK BETWEEN THEM IS CIRISClient#48's ROOT (and #52's).
    //
    // This read `CIRIS_NODE_URL ?: CIRIS_API_URL ?: default`, so on any host
    // that sets CIRIS_API_URL — the QA gate does — every "is the node up?"
    // question was addressed to the AGENT's port. After a run-without-AI
    // hand-off that port is dead by design, which produced #52 whole:
    //
    //   the supervisor probed :8080, called BudgetExpired three times while its
    //   own health check reported :4243 healthy, and on the third attempt
    //   launched a SECOND ciris-server against the same home;
    //   the post-setup hold polled :8080 ~180 times and never exited.
    //
    // A custom port is honoured verbatim through CIRIS_NODE_URL, which is the
    // documented way to move the node. CIRIS_API_URL no longer reaches this
    // value at all — one name, one meaning.
    val nodeUrl = System.getenv("CIRIS_NODE_URL")
        ?: ai.ciris.mobile.shared.api.CIRISApiClient.DEFAULT_LOCAL_NODE_URL

    // WHICH local node this is (CIRISClient#26). Every federation call site —
    // mintUserIdentity, upgradeOwnerToFedId, announceOwnership, getSelfKeyRecord
    // — used to name a hardcoded :4243 while the rest of the app honoured this
    // value, so a client attached to :4343 minted the owner's identity on a node
    // the operator had never attached to. Declared once, here, before anything
    // that could mint a key.
    // `explicit` iff the operator named it; a defaulted address stays
    // inferable so the hand-off may still correct it (#52).
    ai.ciris.mobile.shared.api.CIRISApiClient.setLocalNodeUrl(
        nodeUrl,
        explicit = System.getenv("CIRIS_NODE_URL") != null,
    )
    val backendSupervisor = ai.ciris.mobile.shared.backend.BackendSupervisor(
        probe = { ai.ciris.mobile.shared.backend.DesktopBackendController.probe(nodeUrl) },
        controller = ai.ciris.mobile.shared.backend.DesktopBackendController(pythonRuntime),
        ownership = { ai.ciris.mobile.shared.backend.ownershipOf(nodeUrl) },
        now = { System.currentTimeMillis() },
        log = { println("[backend] $it") },
    )
    val supervisorScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob()
    )
    // A desktop window is foreground for its whole life as far as this is
    // concerned; there is no background-stop policy to fight here.
    backendSupervisor.onResumed()
    backendSupervisor.run(supervisorScope)
    ai.ciris.mobile.shared.backend.BackendStatus.install(backendSupervisor, supervisorScope)

    application {
    val windowState = rememberWindowState(width = 1200.dp, height = 800.dp)

    // Start test automation server if enabled
    val testServer = if (TestAutomationServer.isTestModeEnabled()) {
        val port = System.getenv("CIRIS_TEST_PORT")?.toIntOrNull() ?: 9091
        println("[Desktop] Test mode enabled - starting automation server on port $port")
        val server = TestAutomationServer.getInstance(port)

        // Configure shared module TestAutomation to delegate to our server
        TestAutomation.configure(
            onRegister = { tag, x, y, w, h, text -> server.registerElement(tag, x, y, w, h, text) },
            onUnregister = { tag -> server.unregisterElement(tag) },
            onSetScreen = { screen -> server.currentScreen = screen },
            onClear = { server.clearElements() },
            isEnabled = { true }
        )

        server.also { it.start() }
    } else {
        null
    }

    Window(
        onCloseRequest = {
            // Quit immediately — do NOT block the UI thread waiting
            // for Ktor's grace period or the Python subprocess to
            // tear down. Shutdown work is already registered via the
            // JVM shutdown hook (see addShutdownHook above) and runs
            // on exit.
            //
            // BUT: exitApplication() alone isn't enough to kill the
            // JVM if there are stuck non-daemon threads (Ktor test
            // server accept loop, Python subprocess watchers, etc.).
            // The JVM would keep living invisibly. Force-exit on a
            // short watchdog so the app ALWAYS dies when the user
            // clicks the red close button.
            Thread {
                runCatching { testServer?.stop() }
            }.also { it.isDaemon = true }.start()
            exitApplication()
            Thread {
                try { Thread.sleep(1500) } catch (_: InterruptedException) {}
                // If we're still alive 1.5 s later, force-exit. This
                // runs the JVM shutdown hooks (pythonRuntime.shutdown)
                // on the way out, so the Python subprocess still gets
                // cleaned up correctly.
                System.exit(0)
            }.also { it.isDaemon = true }.start()
        },
        title = "CIRIS Agent",
        state = windowState,
        icon = painterResource("icon.png"),
    ) {
        var accessToken by remember { mutableStateOf("") }

        // Track window position for test automation (screen-absolute coordinates)
        LaunchedEffect(Unit) {
            testServer?.let { server ->
                // Get initial position and set AWT window ref for screenshots
                val frame = window
                server.updateWindowPosition(frame.x, frame.y)
                server.awtWindow = frame

                // Track position changes
                frame.addComponentListener(object : ComponentAdapter() {
                    override fun componentMoved(e: ComponentEvent) {
                        server.updateWindowPosition(frame.x, frame.y)
                    }
                })
            }
        }

        MaterialTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                CIRISApp(
                    accessToken = accessToken,
                    // NODE VENDOR DRIFT #17 (restored after the 2.9.28 re-vendor
                    // reverted both URLs to upstream's split). TWO parameters
                    // upstream, ONE service here. The agent build has a Python
                    // brain on :8080 and a node read API on :4243; this is the
                    // NODE client (CIRISBuild.HAS_AGENT == false), so there is no
                    // brain to address — ciris-server serves the whole surface on
                    // :4243. Pointing apiBaseUrl at the upstream :8080 default
                    // would aim every shared-client call at a dead port.
                    // CIRIS_NODE_URL is the upstream name; CIRIS_API_URL is kept
                    // because this build has always used it to mean the node.
                    // Both from CIRIS_NODE_URL, and NOT from CIRIS_API_URL —
                    // see the note on `nodeUrl` above. `nodeUrl` is already
                    // resolved there and is reused rather than re-derived: two
                    // expressions computing one address is how they drift.
                    apiBaseUrl = nodeUrl,
                    nodeBaseUrl = nodeUrl,
                    pythonRuntime = pythonRuntime,
                    secureStorage = createSecureStorage(),
                    envFileUpdater = createEnvFileUpdater(),
                    googleSignInCallback = null,  // Not supported on desktop
                    purchaseLauncher = null,  // Not supported on desktop
                    deviceAttestationCallback = null,  // Not supported on desktop
                    onTokenUpdated = { newToken ->
                        accessToken = newToken
                    }
                )
            }
        }
    }
}
}
