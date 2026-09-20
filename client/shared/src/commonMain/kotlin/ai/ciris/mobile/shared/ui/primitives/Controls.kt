package ai.ciris.mobile.shared.ui.primitives

import ai.ciris.mobile.shared.platform.TestAutomation
import ai.ciris.mobile.shared.platform.rememberInputSinks
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableWithHandler
import ai.ciris.mobile.shared.ui.theme.CirisShape
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * TOKEN-STYLED CONTROLS. Not primitives — the atoms the primitives and the
 * screens compose from, so no screen ever styles a Material button or field
 * itself. Brand fill, no elevation, 5dp radius, hairline outlines.
 */

@Composable
fun CirisButton(
    label: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val t = CirisTheme.tokens
    val fill = if (danger) t.danger else t.brand
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CirisShape.input,
        elevation = null,
        colors = ButtonDefaults.buttonColors(
            containerColor = fill,
            contentColor = t.onAccent,
            disabledContainerColor = t.sunken,
            disabledContentColor = t.mute,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        modifier = modifier.testableWithHandler(tag) { if (enabled) onClick() },
    ) {
        Text(label, style = CirisTheme.type.body)
    }
}

@Composable
fun CirisTextButton(
    label: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val t = CirisTheme.tokens
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = CirisShape.input,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (danger) t.danger else t.brand,
            disabledContentColor = t.mute,
        ),
        modifier = modifier.testableWithHandler(tag) { if (enabled) onClick() },
    ) {
        Text(label, style = CirisTheme.type.body)
    }
}

/**
 * A text field that is `/input`-drivable BY CONSTRUCTION: it declares its own
 * sink, consumes the automation request addressed to its tag, and reports what
 * it holds. A screen built on this cannot ship a field the gate cannot type
 * into (CIRISClient#28/#31).
 */
@Composable
fun CirisTextField(
    tag: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    mono: Boolean = false,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    rememberInputSinks(tag)
    val request by TestAutomation.textInputRequests.collectAsState()
    LaunchedEffect(request) {
        val r = request ?: return@LaunchedEffect
        if (r.testTag == tag) {
            onValueChange(if (r.clearFirst) r.text else value + r.text)
            TestAutomation.clearTextInputRequest()
        }
    }
    LaunchedEffect(value) { TestAutomation.setInputValue(tag, value) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        textStyle = if (mono) type.signed else type.body,
        placeholder = placeholder?.let { { Text(it, style = type.body, color = t.mute) } },
        shape = CirisShape.input,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = t.ink,
            unfocusedTextColor = t.ink,
            disabledTextColor = t.mute,
            cursorColor = t.brand,
            focusedBorderColor = t.brand,
            unfocusedBorderColor = t.hairlineStrong,
            disabledBorderColor = t.hairline,
            focusedContainerColor = t.sunken,
            unfocusedContainerColor = t.sunken,
            disabledContainerColor = t.sunken,
            focusedPlaceholderColor = t.mute,
            unfocusedPlaceholderColor = t.mute,
        ),
        // Position-tracked with the field's current text, so `/element` reads it back.
        modifier = modifier.fillMaxWidth().testable(tag, value),
    )
}

/** A hairline the primitives share; one place, one width, one token. */
@Composable
fun hairlineStroke(strong: Boolean = false): BorderStroke =
    BorderStroke(CirisShape.hairlineWidth, if (strong) CirisTheme.tokens.hairlineStrong else CirisTheme.tokens.hairline)
