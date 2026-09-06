package ai.ciris.mobile.shared.models

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An OAuth sign-in lands on the free CIRIS models, and can leave.
 *
 * Signing in with Google or Apple entitles the person to CIRIS's hosted models
 * at no cost, and the OAuth ID token IS the credential — there is no key to
 * paste. Getting this wrong is not a cosmetic default: falling to BYOK looks
 * identical on screen to having chosen it, and the difference is a bill.
 *
 * The rule was an inline `when` inside a state assignment, where it could be
 * read and not run. These are the cases that assignment has already got wrong
 * once.
 */
class SetupModeChoiceTest {

    @Test
    fun an_oauth_sign_in_gets_the_free_ciris_models() {
        assertEquals(
            SetupMode.CIRIS_PROXY,
            initialSetupMode(current = null, isCirisEligible = true),
        )
    }

    @Test
    fun the_local_on_device_default_does_not_count_as_a_choice() {
        // THE BUG THIS EXISTS FOR. "Unchosen" has two representations — null and
        // the non-null LOCAL_ON_DEVICE default — so a bare `!= null` guard reads
        // the second as "already chosen", and an eligible OAuth never reaches
        // the proxy. That shipped as "forced BYOK despite a Google login".
        assertEquals(
            SetupMode.CIRIS_PROXY,
            initialSetupMode(current = SetupMode.LOCAL_ON_DEVICE, isCirisEligible = true),
        )
    }

    @Test
    fun someone_who_switched_to_byok_stays_there() {
        // A real choice, and re-authenticating must not silently undo it.
        assertEquals(
            SetupMode.BYOK,
            initialSetupMode(current = SetupMode.BYOK, isCirisEligible = true),
        )
    }

    @Test
    fun someone_who_switched_back_to_the_proxy_stays_there() {
        assertEquals(
            SetupMode.CIRIS_PROXY,
            initialSetupMode(current = SetupMode.CIRIS_PROXY, isCirisEligible = false),
        )
    }

    @Test
    fun without_an_eligible_sign_in_the_only_honest_default_is_byok() {
        // No OAuth token means no credential for the proxy, so offering it would
        // be offering a login that cannot authenticate.
        assertEquals(
            SetupMode.BYOK,
            initialSetupMode(current = null, isCirisEligible = false),
        )
        assertEquals(
            SetupMode.BYOK,
            initialSetupMode(current = SetupMode.LOCAL_ON_DEVICE, isCirisEligible = false),
        )
    }
}
