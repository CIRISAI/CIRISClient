package ai.ciris.mobile.shared.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 0.5.218 (CIRISServer#672): the node PARSES a post-login `redirect_uri` and
 * accepts only a same-origin path or an exact loopback host; an absolute
 * `https://` redirect is refused (`auth.oauth.unsafe_redirect`). The desktop
 * sign-in collects its session over the loopback hand-off (`app_nonce`), so it
 * sends no redirect at all and the node's default, `/`, applies. This pins that:
 * a redirect added here later must be a path or a loopback URL, and this test is
 * where that decision gets made.
 */
class OAuthRedirectContractTest {
    @Test
    fun theBrowserSignInCarriesNoRedirect() {
        val url = CIRISApiClient(baseUrl = "http://127.0.0.1:9")
            .oauthBrowserLoginUrl("google", "nonce123", "http://127.0.0.1:4243")
        assertEquals("http://127.0.0.1:4243/v1/auth/oauth/google/login?app_nonce=nonce123", url)
        assertFalse("redirect" in url)
    }
}
