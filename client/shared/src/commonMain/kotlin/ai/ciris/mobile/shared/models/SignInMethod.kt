package ai.ciris.mobile.shared.models

/**
 * HOW THIS DEVICE'S SESSION WAS ACTUALLY ESTABLISHED — recorded at sign-in,
 * never inferred.
 *
 * The screens used to say "signed in via {provider}" with the provider taken
 * from `getOAuthProviderName()`, which answers "what does this PLATFORM sign
 * in with" — so a desktop owner who signed in with a password on their own
 * node was told "via Google". The node cannot tell us either: `/v1/auth/me`
 * returns the user, not how the session was made. So each login flow records
 * the method it used (`SettingsViewModel.recordSignIn`), sign-out clears it,
 * and when nothing was recorded — a session from before this existed — the
 * sentence names the identity and says nothing about how.
 */
enum class SignInMethod(val stored: String) {
    GOOGLE("google"),
    APPLE("apple"),
    PASSWORD("password");

    companion object {
        const val STORAGE_KEY = "sign_in_method"

        fun fromStored(value: String?): SignInMethod? = entries.firstOrNull { it.stored == value?.trim()?.lowercase() }

        /** A provider id from a native / browser OAuth flow. Unknown ids are unknown, not Google. */
        fun fromProvider(provider: String?): SignInMethod? = fromStored(provider)?.takeIf { it != PASSWORD }
    }
}

/** A localization key and its params — what to say, before the screen resolves it. */
data class SignedInLine(val key: String, val params: Map<String, String>)

/** "Signed in on this device as {identity}", saying how only when it is KNOWN. */
fun signedInLine(identity: String, method: SignInMethod?): SignedInLine = when (method) {
    SignInMethod.GOOGLE -> SignedInLine("mobile.identity_signed_in_via", mapOf("identity" to identity, "provider" to "Google"))
    SignInMethod.APPLE -> SignedInLine("mobile.identity_signed_in_via", mapOf("identity" to identity, "provider" to "Apple"))
    SignInMethod.PASSWORD -> SignedInLine("mobile.identity_signed_in_password", mapOf("identity" to identity))
    null -> SignedInLine("mobile.identity_signed_in_as", mapOf("identity" to identity))
}

/** Settings' sign-in paragraph, by the same rule: the OAuth-is-your-key sentence only for an OAuth sign-in. */
fun settingsSignInLine(method: SignInMethod?): SignedInLine = when (method) {
    SignInMethod.GOOGLE -> SignedInLine("mobile.settings_authentication_desc", mapOf("provider" to "Google"))
    SignInMethod.APPLE -> SignedInLine("mobile.settings_authentication_desc", mapOf("provider" to "Apple"))
    SignInMethod.PASSWORD -> SignedInLine("mobile.settings_authentication_password", emptyMap())
    null -> SignedInLine("mobile.settings_authentication_unknown", emptyMap())
}
