package ai.ciris.mobile.shared.platform

/**
 * Erase this device's local CIRIS state, so the next boot is a genuine first run.
 *
 * Exists because the login screen's "Reset device" promised exactly this and did
 * not do it. The dialog said "This will erase all local data and return to the
 * setup wizard. This cannot be undone."; the handler behind it called
 * `logout()`. The node kept its `.env` and database, so `getSetupStatus` still
 * reported `configExists=true, firstRun=false`, the app returned to Login rather
 * than the wizard, and the next Google sign-in failed 403
 * `auth.oauth.no_local_identity` — signed in fine, but the node had no identity
 * linked to that account. A user reading "data wiped" had no way to tell.
 *
 * Why client-side rather than the real endpoint: the actual reset is
 * `POST /v1/system/data/reset-account`, and it takes `AuthAdminDep`. On the
 * login screen there is no token — that is *why* you are on the login screen —
 * so the authenticated path is unreachable exactly where it is needed. This
 * deletes the app's own files instead, which needs no credential.
 *
 * The security trade is deliberate and narrow: someone holding an unlocked
 * device can wipe local state without signing in. They can already uninstall the
 * app, which destroys the same data, so this grants no capability physical
 * access did not already carry. It does NOT reach anything off-device: no
 * federation row, no peer's copy, no canonical record.
 *
 * Returns true when the state directory is gone afterwards. Callers should
 * restart the process regardless — a half-wiped home that keeps running is worse
 * than either outcome.
 */
/**
 * @param declaredNodeHome the home the RUNNING node reports, when the caller
 *   could ask it. The desktop resolver otherwise reconstructs the backend's
 *   `get_ciris_home()` from this process's environment — which is a guess, and
 *   wrong exactly when it matters: a client connected over `CIRIS_API_URL`, or a
 *   launcher that started the node with a different home, would have the app
 *   erase an unrelated local installation and leave the live node untouched.
 *   `PythonRuntime.desktop` already treats the node's declared path as
 *   authoritative for the claim PIN, for the same reason (Codex, PR #9).
 *   Null when there is nobody to ask; the resolver then falls back to the guess.
 */
expect fun wipeLocalData(declaredNodeHome: String? = null): Boolean
