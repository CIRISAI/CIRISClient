package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.models.federation.InvocationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * CC 4.2.1.2: a consumer MUST distinguish the four accord invocation kinds, and
 * names the forbidden pairing — `lifecycle:active` (resumption from a halt)
 * shown as a `notify`. The old mapping's `else` did exactly that (CSD-067 §2.2).
 */
class AccordInvocationKindTest {

    private fun badgeFor(wire: String) = invocationBadgeKey(InvocationKind.fromWire(wire))

    @Test
    fun aResumptionIsItsOwnKindAndNeverBadgedAsNotify() {
        assertEquals(InvocationKind.LIFECYCLE_ACTIVE, InvocationKind.fromWire("lifecycle:active"))
        assertEquals("mobile.accord_kind_reactivated", badgeFor("lifecycle:active"))
        assertNotEquals("mobile.accord_kind_notify", badgeFor("lifecycle:active"))
        assertNotEquals("mobile.accord_kind_constitutional", badgeFor("lifecycle:active"))
    }

    @Test
    fun theThreeInvokeKindsKeepTheirBadgesInEitherCase() {
        assertEquals("mobile.accord_kind_constitutional", badgeFor("CONSTITUTIONAL"))
        assertEquals("mobile.accord_kind_notify", badgeFor("notify"))
        assertEquals("mobile.accord_kind_notify", badgeFor("NOTIFY"))
        assertEquals("mobile.accord_kind_drill", badgeFor("drill"))
        assertEquals("mobile.accord_kind_drill", badgeFor("DRILL"))
    }

    @Test
    fun anUnknownKindIsExplicitlyUnknownNotSilentlyNotify() {
        for (wire in listOf("lifecycle:suspended", "", "halt", "invoke")) {
            assertEquals(InvocationKind.UNKNOWN, InvocationKind.fromWire(wire), wire)
            assertEquals("mobile.accord_kind_unknown", badgeFor(wire), wire)
        }
        assertEquals(InvocationKind.UNKNOWN, InvocationKind.fromWire(null))
    }

    @Test
    fun theBindingNoteHasFourArmsAndNoNotifyFallthrough() {
        // The binding sentence under an invocation card branched on three values
        // with `else -> notify`, so a resumption (and an unknown kind) read as a
        // single-holder broadcast — the pairing CC 4.2.1.2 forbids by name.
        assertEquals("mobile.accord_binding_constitutional", invocationBindingKey(InvocationKind.CONSTITUTIONAL))
        assertEquals("mobile.accord_binding_drill", invocationBindingKey(InvocationKind.DRILL))
        assertEquals("mobile.accord_binding_notify", invocationBindingKey(InvocationKind.NOTIFY))
        assertEquals("mobile.accord_binding_reactivated", invocationBindingKey(InvocationKind.LIFECYCLE_ACTIVE))
        assertEquals("mobile.accord_binding_unknown", invocationBindingKey(InvocationKind.UNKNOWN))
        val keys = InvocationKind.entries.map { invocationBindingKey(it) }
        assertEquals(keys.size, keys.toSet().size, "two kinds share a binding note: $keys")
    }

    @Test
    fun everyKindHasADistinctBadge() {
        val keys = InvocationKind.entries.map { invocationBadgeKey(it) }
        assertEquals(keys.size, keys.toSet().size, "two kinds share a badge: $keys")
    }
}
