package ai.ciris.mobile.shared.auth

import ai.ciris.mobile.shared.models.SignInMethod
import ai.ciris.mobile.shared.platform.SecureStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ONE SIGN-OUT (CIRISClient#51).
 *
 * Every way out of a session that the person chooses — Settings' and My
 * Identity's `btn_logout`, the desktop menu's `menu_logout`, and My things'
 * `btn_my_things_sign_out`, the one every install mode has — runs [now], and
 * [now] is the only thing that does. Before this, CIRISApp carried the same
 * three lines at three call sites; a fourth copy for the run-without-AI home
 * would have been a second sign-out path waiting to drift from the first.
 *
 * What it does, in order: stop the chat's polling (a poll on the about-to-be-
 * revoked token fires a 401 that races the next sign-in), then [logout] —
 * `SettingsViewModel.logout`, which clears the session from secure storage
 * ([clearSession]) and revokes it at the node — then [toLogin], which drops
 * the in-memory token and lands on Login. Dropping the token is what resets
 * consent, contacts and chat state: those resets key off the token going null
 * in CIRISApp, so they cannot be missed by a path that forgot to call them.
 *
 * My things' control confirms first ([ask] → [confirm]); the others are the
 * controls harnesses already drive and stay one tap.
 */
class SignOut(
    private val stopChat: () -> Unit,
    private val logout: (onComplete: () -> Unit) -> Unit,
    private val toLogin: () -> Unit,
) {
    private val _confirming = MutableStateFlow(false)

    /** True while the sign-out confirm is open. */
    val confirming: StateFlow<Boolean> = _confirming.asStateFlow()

    /** Open the confirm. Nothing is signed out yet. */
    fun ask() { _confirming.value = true }

    /** Close the confirm; still signed in. */
    fun dismiss() { _confirming.value = false }

    /** The person confirmed: close the confirm and sign out. */
    fun confirm() {
        _confirming.value = false
        now()
    }

    /** Sign out. The one implementation. */
    fun now() {
        stopChat()
        logout { toLogin() }
    }
}

/** What signing out removes from secure storage — the session, and how it was made. */
interface SessionStore {
    suspend fun deleteAccessToken(): Result<Unit>
    suspend fun delete(key: String): Result<Unit>
}

/** The session's keys besides the access token. Settings, language and the node profiles are NOT here: they stay. */
val SESSION_KEYS: List<String> = listOf("refresh_token", "user_id", "user_email", SignInMethod.STORAGE_KEY)

/**
 * Clear this device's session from [store]. The access token must go — a
 * failure there throws, because a sign-out that leaves the token would be
 * restored on the next launch; the other keys are best-effort.
 */
suspend fun clearSession(store: SessionStore) {
    store.deleteAccessToken().getOrThrow()
    for (k in SESSION_KEYS) store.delete(k)
}

/** The platform's secure storage, seen as the session it holds. */
fun SecureStorage.asSessionStore(): SessionStore {
    val storage = this
    return object : SessionStore {
        override suspend fun deleteAccessToken(): Result<Unit> = storage.deleteAccessToken()
        override suspend fun delete(key: String): Result<Unit> = storage.delete(key)
    }
}
