package ai.ciris.mobile.shared.ui.shell

import ai.ciris.mobile.shared.auth.SignOut
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.ui.primitives.ConfirmFact
import ai.ciris.mobile.shared.ui.primitives.ConfirmSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * The sign-out confirm (CIRISClient#51): three facts — what is signed out, what
 * stays on this device, how to sign back in. Tagged `sheet_sign_out`,
 * `btn_sign_out_confirm`, `btn_sign_out_cancel`. Confirming runs
 * [SignOut.confirm], which is the same sign-out every other control runs.
 */
@Composable
fun SignOutConfirm(signOut: SignOut) {
    val open by signOut.confirming.collectAsState()
    if (!open) return
    ConfirmSheet(
        title = localizedString("mobile.sign_out_title"),
        facts = listOf(
            ConfirmFact(localizedString("mobile.sign_out_fact_ends"), localizedString("mobile.sign_out_ends")),
            ConfirmFact(localizedString("mobile.sign_out_fact_stays"), localizedString("mobile.sign_out_stays")),
            ConfirmFact(localizedString("mobile.sign_out_fact_back"), localizedString("mobile.sign_out_back")),
        ),
        confirmLabel = localizedString("mobile.identity_sign_out"),
        onConfirm = { signOut.confirm() },
        onDismiss = { signOut.dismiss() },
        tagPrefix = "sign_out",
    )
}
