package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.api.AddressValidationResult
import ai.ciris.mobile.shared.api.DuplicateCheckResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CSD-057: the send opens its ConfirmSheet only when every check has answered
 * and passed. The old button fired on a well-formed address alone.
 */
class WalletSendGateTest {
    private val addr = "0x" + "a".repeat(40)
    private val ok = AddressValidationResult(valid = true, checksumValid = true)
    private val notDup = DuplicateCheckResult(isDuplicate = false)

    @Test
    fun aCleanSendMayBeConfirmed() {
        assertNull(walletSendGate(addr, "1.50", ok, validating = false, duplicate = notDup))
    }

    @Test
    fun anUnansweredCheckBlocks() {
        assertEquals(WalletSendBlock.VALIDATING, walletSendGate(addr, "1", null, validating = true, duplicate = notDup))
        assertEquals(WalletSendBlock.NOT_VALIDATED, walletSendGate(addr, "1", null, validating = false, duplicate = notDup))
        assertEquals(WalletSendBlock.DUPLICATE_UNCHECKED, walletSendGate(addr, "1", ok, validating = false, duplicate = null))
    }

    @Test
    fun aFailedCheckBlocks() {
        assertEquals(WalletSendBlock.INVALID_ADDRESS,
            walletSendGate(addr, "1", AddressValidationResult(valid = false, checksumValid = false), false, notDup))
        assertEquals(WalletSendBlock.BAD_CHECKSUM,
            walletSendGate(addr, "1", AddressValidationResult(valid = true, checksumValid = false), false, notDup))
        assertEquals(WalletSendBlock.ZERO_ADDRESS,
            walletSendGate(addr, "1", AddressValidationResult(valid = true, checksumValid = true, isZeroAddress = true), false, notDup))
        assertEquals(WalletSendBlock.DUPLICATE, walletSendGate(addr, "1", ok, false, DuplicateCheckResult(isDuplicate = true)))
    }

    @Test
    fun theInputsThemselves() {
        assertEquals(WalletSendBlock.NO_RECIPIENT, walletSendGate("", "1", ok, false, notDup))
        assertEquals(WalletSendBlock.BAD_FORMAT, walletSendGate("0x12", "1", ok, false, notDup))
        assertEquals(WalletSendBlock.NO_AMOUNT, walletSendGate(addr, "", ok, false, notDup))
        assertEquals(WalletSendBlock.BAD_AMOUNT, walletSendGate(addr, "-2", ok, false, notDup))
        assertEquals(WalletSendBlock.BAD_AMOUNT, walletSendGate(addr, "abc", ok, false, notDup))
    }
}
