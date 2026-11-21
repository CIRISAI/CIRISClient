package ai.ciris.mobile.shared.ceremony

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tool's own texts (genesis-ceremony.rs, signers.rs at 0dabc27e). */
class CeremonyOutputTest {
    @Test fun the_touch_line_n_of_3() {
        val t = "PIN accepted. Do not touch the YubiKey until this tool says \"Touch the YubiKey now\".\n" +
            ">>> Touch the YubiKey now (1 of 3). <<<\nwrote D/holders/A1/record.cose\n>>> Touch the YubiKey now (2 of 3). <<<\n"
        assertEquals(CeremonyOutput.Touch(2, 3), CeremonyOutput.lastTouch(t))
        assertNull(CeremonyOutput.lastTouch("PIN accepted. Do not touch the YubiKey until this tool says \"Touch the YubiKey now\"."))
    }

    @Test fun prompts_are_read_from_the_unfinished_last_line() {
        assertEquals(CeremonyOutput.Prompt.CONFIRM, CeremonyOutput.pendingPrompt("ONE SITTING for holder A1\nType yes to sign (do NOT touch the YubiKey yet): "))
        assertEquals(CeremonyOutput.Prompt.SWAP_TOKEN, CeremonyOutput.pendingPrompt("holder A1: token serial 1\nRemove holder A1's YubiKey, insert the BACKUP YubiKey, then press Enter: "))
        assertEquals(CeremonyOutput.Prompt.PIN, CeremonyOutput.pendingPrompt("YubiKey PIV PIN (slot 9c): "))
        assertNull(CeremonyOutput.pendingPrompt("Type yes to sign (do NOT touch the YubiKey yet): yes\n"))
    }

    @Test fun refusals_by_their_stable_text() {
        assertEquals(CeremonyOutput.Refusal.SERIAL_COLLISION, CeremonyOutput.refusalOf("Error: the backup token's serial 123 is the holder token's: insert the BACKUP YubiKey"))
        assertEquals(CeremonyOutput.Refusal.SERIAL_COLLISION, CeremonyOutput.refusalOf("Error: the backup token holds the holder's own key: insert the BACKUP YubiKey"))
        assertEquals(CeremonyOutput.Refusal.RECOVERY_KEY, CeremonyOutput.refusalOf("Error: ceremony_recovery_key_is_signing_key: holder A1's recovery key is its own signing key"))
        assertEquals(CeremonyOutput.Refusal.RECOVERY_KEY, CeremonyOutput.refusalOf("Error: holder B1's signing or recovery key is A1's signing key; refusing"))
        assertEquals(CeremonyOutput.Refusal.NO_TCP_HINT, CeremonyOutput.refusalOf("Error: a serve seat carries at least one tcp dial hint (CC 2.6.1.11)"))
        assertEquals(CeremonyOutput.Refusal.HALT_MISSING, CeremonyOutput.refusalOf("Error: halt coverage incomplete: Some(..)"))
        assertEquals(CeremonyOutput.Refusal.WRONG_TOKEN, CeremonyOutput.refusalOf("Error: the token's serial Some(2) is not the prepared Some(1); refusing"))
        assertEquals(CeremonyOutput.Refusal.NOT_CONFIRMED, CeremonyOutput.refusalOf("Error: not confirmed; nothing was signed"))
        assertEquals(CeremonyOutput.Refusal.OTHER, CeremonyOutput.refusalOf("Error: something new"))
    }

    @Test fun the_error_text_is_the_tools_own() {
        assertEquals("Error: not confirmed; nothing was signed", CeremonyOutput.errorText("ONE SITTING\nType yes to sign: no\nError: not confirmed; nothing was signed\n"))
        assertNull(CeremonyOutput.errorText("FINAL GATE PASSED\n"))
    }

    @Test fun an_otp_burst_is_noticed() {
        assertTrue(CeremonyOutput.otpTyped("That was a YubiKey one-time password: a touch while no signature was waiting"))
        assertFalse(CeremonyOutput.otpTyped("Type yes to sign"))
    }

    @Test fun the_echo_guard_masks_the_pin_then_forgets_it() {
        val g = PinEchoGuard(windowMs = 1000)
        val copy = "135790".toCharArray()
        g.arm(copy, nowMs = 0)
        assertEquals("\n••••••\nPIN accepted.", g.scrub("\n135790\nPIN accepted.", nowMs = 10))
        assertEquals("135790", g.scrub("135790", nowMs = 2000).let { g.scrub("135790", 2001) })
        assertTrue(copy.all { it == '\u0000' }, "the guard's copy is wiped when the window ends")
    }

    @Test fun cleaning_terminal_text() =
        assertEquals("a\nb\n", CeremonyOutput.clean("a\r\n\u001B[1mb\u001B[0m\r\n"))
}
