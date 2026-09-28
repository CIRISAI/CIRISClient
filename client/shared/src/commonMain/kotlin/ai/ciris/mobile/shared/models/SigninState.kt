package ai.ciris.mobile.shared.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /v1/auth/signin-state` — what signing in would do on this node, RIGHT
 * NOW, asked before anyone tries (CIRISServer `src/auth/oauth.rs`, #439;
 * unauthenticated). CSD-081 reads [newIdentity] so the Login screen can say "a
 * new account would be refused here" before someone is refused, rather than
 * inferring it from the provider list and the owner hint (CIRISClient#110).
 *
 * Only the fields the card uses are modelled; the rest (`web_signin`,
 * `callback_base`, `exchange_query_key`, `caller_is_loopback`) are the browser
 * page's and are ignored.
 */
@Serializable
data class SigninState(
    val claimed: Boolean = false,
    val managed: Boolean = false,
    val providers: List<String> = emptyList(),
    /** `loopback_handoff` | `exchange_code`, per caller. */
    @SerialName("session_delivery")
    val sessionDelivery: String? = null,
    @SerialName("new_identity")
    val newIdentity: NewIdentityOutcome? = null,
)

/**
 * What an account this node has never seen would get: `claims_this_node`,
 * `admitted_as_observer` or `refused` (then [reasonId] is
 * `auth.oauth.no_local_identity` and [remedy] is the node's English).
 */
@Serializable
data class NewIdentityOutcome(
    val outcome: String,
    @SerialName("reason_id")
    val reasonId: String? = null,
    val remedy: String? = null,
) {
    val isRefused: Boolean get() = outcome == "refused"
}
