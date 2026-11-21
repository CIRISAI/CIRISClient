package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.ceremony.CeremonyRunner
import ai.ciris.mobile.shared.ceremony.platformCeremonyRunner
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.capability.NodeEdition
import ai.ciris.mobile.shared.platform.PlatformLogger
import ai.ciris.mobile.shared.platform.currentTimeMillis
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.viewmodels.GenesisCeremonyViewModel
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * WHERE THE GENESIS CEREMONY IS OFFERED (CSD-069). The ceremony is offline: it runs
 * the bundled tool over a directory on a USB stick and never uses the attached node.
 * The genesis comes BEFORE any v62 node exists, so the entry cannot wait for one: it
 * is shown whenever this build carries the tool, on every edition and with whatever
 * node is attached (or none). A build without the tool, every release build, shows
 * nothing new.
 */
object GenesisCeremonyEntryGate {
    @Suppress("UNUSED_PARAMETER") // the edition is taken to pin that it does not matter
    fun shows(runner: CeremonyRunner?, edition: NodeEdition?): Boolean = runner?.bundled == true
}

/** The desktop runner, when this build carries the tool; otherwise null and nothing is drawn. */
@Composable
fun rememberBundledCeremonyRunner(): CeremonyRunner? =
    remember { platformCeremonyRunner()?.takeIf { GenesisCeremonyEntryGate.shows(it, null) } }

/** The ceremony screen for [runner], with its own state for as long as it is open. */
@Composable
fun GenesisCeremonyOverlay(runner: CeremonyRunner, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val vm = remember(runner) {
        GenesisCeremonyViewModel(runner, scope, { currentTimeMillis() }) { line -> PlatformLogger.i("GenesisCeremony", line) }
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        GenesisCeremonyScreen(vm, onClose)
    }
}

/**
 * Before anyone is signed in (the sign-in and setup screens on a fresh desktop), a
 * corner entry: the Accord screen cannot be reached there, and the ceremony needs no
 * signed-in node. Drawn last in the screen's Box, so it sits on top.
 */
@Composable
fun BoxScope.GenesisCeremonyCorner() {
    val runner = rememberBundledCeremonyRunner() ?: return
    var open by remember { mutableStateOf(false) }
    if (open) {
        GenesisCeremonyOverlay(runner) { open = false }
        return
    }
    OutlinedButton(
        onClick = { open = true },
        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
            .testableClickable("btn_genesis_ceremony_corner") { open = true },
    ) { Text(localizedString("mobile.genesis_open")) }
}
