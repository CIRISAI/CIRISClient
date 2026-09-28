package ai.ciris.mobile.shared.viewmodels

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CSD-010: the status-bar wallet badge. A failed `GET /v1/wallet/status`
 * used to render as "set up wallet" — a claim about the person made from a
 * read that never happened. It is its own state now.
 */
class WalletBadgeStateTest {
    @Test
    fun aFailedReadIsNotNoWallet() {
        val before = WalletStatus(isLoaded = true, hasWallet = true, balance = "12.00")
        val failed = before.copy(readFailed = true)
        assertEquals(WalletBadgeState.NOT_READ, failed.badgeState())
        assertEquals(WalletBadgeState.NO_WALLET, WalletStatus(isLoaded = true, hasWallet = false).badgeState())
    }

    @Test
    fun theOtherStatesStillRead() {
        assertEquals(WalletBadgeState.INITIALIZING, WalletStatus(isInitializing = true).badgeState())
        assertEquals(WalletBadgeState.FUNDED, WalletStatus(hasWallet = true, balance = "3.50").badgeState())
        assertEquals(WalletBadgeState.EMPTY, WalletStatus(hasWallet = true, balance = "0.00").badgeState())
    }
}
