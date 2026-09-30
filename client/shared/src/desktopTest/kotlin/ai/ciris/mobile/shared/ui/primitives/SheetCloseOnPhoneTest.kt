package ai.ciris.mobile.shared.ui.primitives

import ai.ciris.mobile.shared.ceg.Dim
import ai.ciris.mobile.shared.platform.TestAutomation
import ai.ciris.mobile.shared.testing.TestAutomationState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A sheet's way out must be on screen on a phone, without scrolling.
 *
 * Android, matrix run 36752849889 (CSD-005 and CSD-006): the receipt sheet
 * filled the emulator, Close sat below the fold at the end of the sheet's own
 * scroll, and that scroll was a plain `verticalScroll` automation could not
 * drive — `/scroll` moved the People list BEHIND the sheet instead. The open
 * sheet's drivable list carried every receipt row and no `btn_receipt_close`,
 * and `/click` answered 404 "composed but off screen".
 *
 * Rendered headless at a small phone (360 x 640 dp) and read back through the
 * same registration a harness sees: the exit's bounds must lie in the window.
 */
class SheetCloseOnPhoneTest {

    private data class Box(val x: Int, val y: Int, val w: Int, val h: Int)

    private val registered = mutableMapOf<String, Box>()

    private val density = 3f
    private val widthPx = (360 * density).toInt()
    private val heightPx = (640 * density).toInt()

    private fun armAutomation() = TestAutomation.configure(
        onRegister = { tag, x, y, w, h, _ -> registered[tag] = Box(x, y, w, h) },
        onUnregister = { registered.remove(it) },
        onSetScreen = {},
        onClear = { registered.clear() },
        isEnabled = { true },
    )

    @AfterTest
    fun disarm() = TestAutomation.configure(
        onRegister = { _, _, _, _, _, _ -> },
        onUnregister = {},
        onSetScreen = {},
        onClear = {},
        isEnabled = { false },
    )

    /**
     * Render, let the sheet finish animating in, and read what registered. With
     * [scrollContainer], then post the same `/scroll` request a harness posts —
     * through [TestAutomationState], naming the container — and read again: the
     * sheet's BODY must be the thing that moves.
     */
    private fun render(
        scrollContainer: String? = null,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ): Pair<Map<String, Box>, Map<String, Box>?> {
        armAutomation()
        return ImageComposeScene(width = widthPx, height = heightPx, density = Density(density), content = content).use {
            var t = 0L
            fun frames(n: Int) = repeat(n) { _ -> it.render(t); t += 16_000_000L }
            frames(120) // ~2 s: the sheet slides in
            val before = registered.toMap()
            val after = scrollContainer?.let { name ->
                TestAutomationState.requestScroll(testTag = "", direction = "down", amount = 20_000, container = name)
                frames(120)
                registered.toMap()
            }
            before to after
        }
    }

    private fun assertOnScreen(tag: String, seen: Map<String, Box>) {
        val b = assertNotNull(seen[tag], "$tag must be composed and registered at all: ${seen.keys}")
        assertTrue(
            b.w > 0 && b.h > 0 && b.x >= 0 && b.y >= 0 && b.x + b.w <= widthPx && b.y + b.h <= heightPx,
            "$tag must lie within a 360x640 dp phone (${widthPx}x$heightPx px); it is at $b (a zero box is how the harness sees composed-but-off-screen)",
        )
    }

    /** The receipt Android showed: five facts, a for-agent row, notes, two acts. */
    private fun phoneReceipt() = Receipt(
        id = "ciris-gate-peer-9893b757-user-riv6mtomfo",
        subject = Fact.Wire("ciris-gate-peer-9893b757-user-riv6mtomfo-kxcf"),
        attester = Fact.Wire(
            "gate-gate-peer-22es",
            "The person who consented — signed with their own identity, not by this node.",
        ),
        scope = Fact.Wire("everyone"),
        dimension = Dim.consentKind,
        dimensionValue = Fact.Wire("consent:replication:v1"),
        rule = Fact.Wire("capacity:, chat:, ownership:, self:delegates_to:, trace:"),
        forAgent = Fact.Wire("ciris-server-agent-s37q"),
        holders = null,
        notes = listOf(ReceiptNote("peer", "Met at the community meeting; confirmed the code in person.")),
        acts = listOf(
            ReceiptAct("Open chat", "btn_receipt_act_chat", {}),
            ReceiptAct("Remove", "btn_receipt_act_remove", {}),
        ),
        scopeNote = "The grant itself is a public record. What you send each other is not.",
    )

    @Test
    fun the_receipt_close_is_on_screen_on_a_phone() {
        val (seen, _) = render { ReceiptSheet(receipt = phoneReceipt(), onDismiss = {}) }
        assertOnScreen("btn_receipt_close", seen)
    }

    @Test
    fun the_receipt_body_scrolls_under_automation_and_close_stays() {
        val (_, scrolled) = render(scrollContainer = "sheet_receipt") {
            ReceiptSheet(receipt = phoneReceipt(), onDismiss = {})
        }
        val after = assertNotNull(scrolled)
        assertOnScreen("btn_receipt_act_remove", after) // the last thing in the body
        assertOnScreen("btn_receipt_close", after)
    }

    @Test
    fun the_confirm_buttons_stay_and_every_fact_is_reachable_on_a_phone() {
        val long = List(12) { "A fact long enough to wrap across the width of a small phone, line $it." }
            .joinToString(" ")
        val (seen, scrolled) = render(scrollContainer = "sheet_confirm") {
            ConfirmSheet(
                title = "Share this with the whole community?",
                facts = listOf(
                    ConfirmFact("What is shared", long),
                    ConfirmFact("Who can see it", long),
                    ConfirmFact("How to undo it", long),
                ),
                confirmLabel = "Share",
                onConfirm = {},
                onDismiss = {},
                note = long,
            )
        }
        assertOnScreen("btn_confirm_cancel", seen)
        assertOnScreen("btn_confirm_confirm", seen)
        // The facts are what the person confirms; the last of them must be reachable.
        val after = assertNotNull(scrolled)
        assertOnScreen("confirm_note", after)
        assertOnScreen("btn_confirm_confirm", after)
    }
}
