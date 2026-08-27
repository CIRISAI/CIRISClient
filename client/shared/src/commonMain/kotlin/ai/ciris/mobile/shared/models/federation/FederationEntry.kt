package ai.ciris.mobile.shared.models.federation

/**
 * May this device enter as its federation identity right now?
 *
 * ## One credential, and the account session is it
 *
 * A long-lived federation identity sitting on the device is **not proof of who
 * is holding the device**. Entering on its presence alone would be a second,
 * credential-less door beside the account login — and the quieter one, because
 * it looks like an ordinary sign-in button rather than a bypass.
 *
 * This repo used to answer the question by not having the door: the login screen
 * carried a comment saying there was no fedID sign-in option *by design*, and
 * the startup probe computed an identity that nothing rendered. That was safe
 * and it was also a feature the consumers have and this tree did not, so
 * adopting `ciris-client` would have silently removed it from them. The door is
 * now here, and this function is why that is safe: it exists, and it is shut
 * unless a real account session is already open.
 *
 * ## Why there is no per-fedID PIN
 *
 * A second secret on the same door rebuilds the parallel-credential path this
 * closes — two ways in, each with its own recovery story, and the weaker one
 * decides the security of the pair. YubiKey-backed fedIDs are the exception and
 * need nothing from us: the token enforces PIN and touch at signing time, which
 * is a property of the key, not of this screen.
 *
 * ## Why a token is the right thing to check
 *
 * [accessToken] is non-null only after a real login in this run, or a stored
 * session the backend verified at startup. It is not a local flag the UI sets
 * about itself. And the node agrees independently: `resolve_user_signer` will
 * not sign with the fedID without the live owner session either, so a client
 * that got this wrong would be refused one layer down rather than trusted.
 *
 * @param accessToken the current owner session token, or null when there is none.
 */
fun mayEnterWithFederationIdentity(accessToken: String?): Boolean =
    !accessToken.isNullOrBlank()
