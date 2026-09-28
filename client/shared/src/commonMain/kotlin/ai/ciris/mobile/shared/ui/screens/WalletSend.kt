package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.api.AddressValidationResult
import ai.ciris.mobile.shared.api.DuplicateCheckResult
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.ui.primitives.ConfirmFact
import androidx.compose.runtime.Composable

/**
 * CSD-057 · the send, decided in one place.
 *
 * A USDC send is irreversible, so it goes behind a ConfirmSheet naming three
 * facts (who receives, what changes, who signs), and the sheet only opens once
 * every check the screen runs has ANSWERED and passed. The checks used to be
 * decorative: an invalid address or a repeat-send warning showed an icon and
 * the button still fired, and both checks are debounced so Send could beat
 * them. [walletSendGate] is the pure rule; the page reads it.
 */
enum class WalletSendBlock(val key: String) {
    NO_RECIPIENT("mobile.wallet_enter_recipient"),
    BAD_FORMAT("mobile.wallet_invalid_format"),
    VALIDATING("mobile.wallet_gate_validating"),
    NOT_VALIDATED("mobile.wallet_gate_not_validated"),
    INVALID_ADDRESS("mobile.wallet_gate_invalid_address"),
    BAD_CHECKSUM("mobile.wallet_gate_checksum"),
    ZERO_ADDRESS("mobile.wallet_gate_zero"),
    NO_AMOUNT("mobile.wallet_enter_amount"),
    BAD_AMOUNT("mobile.wallet_invalid_amount"),
    DUPLICATE_UNCHECKED("mobile.wallet_gate_unchecked"),
    DUPLICATE("mobile.wallet_gate_duplicate"),
}

/**
 * Why the send may not be confirmed yet; null when it may. A check that has
 * not answered (still running, or it threw and left null) BLOCKS: an unanswered
 * check is not a passed one.
 */
fun walletSendGate(
    recipient: String,
    amount: String,
    validation: AddressValidationResult?,
    validating: Boolean,
    duplicate: DuplicateCheckResult?,
): WalletSendBlock? {
    if (recipient.isBlank()) return WalletSendBlock.NO_RECIPIENT
    if (!recipient.startsWith("0x") || recipient.length != 42) return WalletSendBlock.BAD_FORMAT
    if (validating) return WalletSendBlock.VALIDATING
    if (validation == null) return WalletSendBlock.NOT_VALIDATED
    if (validation.isZeroAddress) return WalletSendBlock.ZERO_ADDRESS
    if (!validation.valid) return WalletSendBlock.INVALID_ADDRESS
    if (!validation.checksumValid) return WalletSendBlock.BAD_CHECKSUM
    if (amount.isBlank()) return WalletSendBlock.NO_AMOUNT
    val value = amount.toDoubleOrNull()
    if (value == null || value <= 0) return WalletSendBlock.BAD_AMOUNT
    if (duplicate == null) return WalletSendBlock.DUPLICATE_UNCHECKED
    if (duplicate.isDuplicate) return WalletSendBlock.DUPLICATE
    return null
}

/**
 * The three facts of a send. Who signs is the agent's wallet key — Identity =
 * Wallet (CC 3.3.10) — so the person authorises here and the node signs; the
 * sheet says so rather than implying a second signature that never comes.
 */
@Composable
fun walletSendFacts(recipient: String, amount: String, currency: String): List<ConfirmFact> = listOf(
    ConfirmFact(localizedString("mobile.wallet_confirm_fact_to"), recipient, mono = true),
    ConfirmFact(
        localizedString("mobile.wallet_confirm_fact_what"),
        localizedString("mobile.wallet_confirm_what", mapOf("amount" to amount, "currency" to currency)),
    ),
    ConfirmFact(localizedString("mobile.wallet_confirm_fact_signer"), localizedString("mobile.wallet_confirm_signer")),
)
