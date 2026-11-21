package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.TestAutomation
import ai.ciris.mobile.shared.platform.rememberInputSinks
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.viewmodels.GenesisCeremonyViewModel
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation

/** The PIN field: masked, sensitive to automation (never reported), cleared the moment it is sent. */
@Composable
internal fun GenesisCeremonyPinRow(vm: GenesisCeremonyViewModel) {
    var pin by remember { mutableStateOf("") }
    rememberInputSinks("input_genesis_pin", sensitive = true)
    val req by TestAutomation.textInputRequests.collectAsState()
    LaunchedEffect(req) {
        val r = req ?: return@LaunchedEffect
        if (r.testTag == "input_genesis_pin") {
            pin = if (r.clearFirst) r.text else pin + r.text
            TestAutomation.clearTextInputRequest()
        }
    }
    fun send() {
        val chars = pin.toCharArray()
        pin = ""
        vm.sendPin(chars) // wiped by the time this returns
    }
    Text(localizedString("mobile.genesis_prompt_pin"), fontWeight = FontWeight.Bold, modifier = Modifier.testable("txt_genesis_prompt"))
    OutlinedTextField(
        value = pin, onValueChange = { pin = it }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        label = { Text(localizedString("mobile.genesis_pin_label")) },
        modifier = Modifier.fillMaxWidth().testable("input_genesis_pin", ""),
    )
    Button(onClick = { send() }, modifier = Modifier.testableClickable("btn_genesis_send_pin") { send() }) {
        Text(localizedString("mobile.genesis_send_pin"))
    }
}

