package ai.ciris.mobile.shared.ui.shell

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.ui.glyphs.Glyph
import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.nav.CirclesNav
import ai.ciris.mobile.shared.ui.nav.Instrument
import ai.ciris.mobile.shared.ui.primitives.CirisTextButton
import ai.ciris.mobile.shared.ui.theme.BrightnessPreference
import ai.ciris.mobile.shared.ui.theme.CirisShape
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp

/**
 * MY THINGS — who you are, and the instruments. Not a sixth circle.
 *
 * The avatar at the top left opens this sheet on every build; the desktop rail
 * also lists the same five instruments below the circles, with the same
 * tags, so `btn_my_things → nav_instrument_<id> → nav_epistemic_<surface>`
 * reaches a surface identically on a phone and a laptop.
 *
 * The brightness control (Light / System / Dark) lives here now, with the
 * other instruments, instead of pinned under the rail where it competed with
 * the circles. Same `btn_brightness_*` tags.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyThingsSheet(
    hasAgent: Boolean,
    version: String,
    brightness: BrightnessPreference,
    onBrightness: (BrightnessPreference) -> Unit,
    onInstrument: (Instrument) -> Unit,
    onDismiss: () -> Unit,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = t.raised,
        contentColor = t.ink,
        shape = CirisShape.sheet,
        tonalElevation = 0.dp,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().testable("sheet_my_things").padding(vertical = 12.dp).navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(localizedString("nav.my_things"), style = type.title, color = t.ink, modifier = Modifier.weight(1f))
                Text(version, style = type.signed, color = t.mute, modifier = Modifier.testable("shell_version", version))
            }
            Spacer(Modifier.height(4.dp))
            for (i in CirclesNav.instruments(hasAgent)) {
                InstrumentRow(
                    label = localizedString(i.labelKey),
                    glyph = i.glyph,
                    tag = i.tag,
                    onClick = { onInstrument(i) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(thickness = CirisShape.hairlineWidth, color = t.hairline, modifier = Modifier.padding(horizontal = 18.dp))
            Spacer(Modifier.height(10.dp))
            BrightnessStrip(brightness, onBrightness, modifier = Modifier.padding(horizontal = 18.dp))
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                CirisTextButton(localizedString("common_close"), tag = "btn_my_things_close", onClick = onDismiss)
            }
        }
    }
}

/** Light · System · Dark as three chips. The same tags the old rail strip carried. */
@Composable
fun BrightnessStrip(
    brightness: BrightnessPreference,
    onBrightness: (BrightnessPreference) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    Column(modifier = modifier.testable("sidebar_brightness_strip")) {
        Text(localizedString("settings.brightness").uppercase(), style = type.label, color = t.mute)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((pref, tag, key) in listOf(
                Triple(BrightnessPreference.LIGHT, "btn_brightness_light", "settings.brightness_light"),
                Triple(BrightnessPreference.SYSTEM, "btn_brightness_system", "settings.brightness_system"),
                Triple(BrightnessPreference.DARK, "btn_brightness_dark", "settings.brightness_dark"),
            )) {
                val on = pref == brightness
                Row(
                    modifier = Modifier
                        .clip(CirisShape.chip)
                        .background(if (on) t.brand.copy(alpha = 0.12f).compositeOver(t.raised) else t.raised)
                        .border(CirisShape.hairlineWidth, if (on) t.brand else t.hairlineStrong, CirisShape.chip)
                        .testableClickable(tag, localizedString(key)) { onBrightness(pref) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (on) Glyph(GlyphName.CHECK, tint = t.brand, size = 12.dp)
                    Text(localizedString(key), style = type.label, color = if (on) t.brand else t.dim)
                }
            }
        }
    }
}
