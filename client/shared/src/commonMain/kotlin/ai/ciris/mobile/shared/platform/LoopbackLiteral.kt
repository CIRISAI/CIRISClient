package ai.ciris.mobile.shared.platform

/**
 * LOOPBACK AS A LITERAL, NEVER A NAME TO RESOLVE.
 *
 * The maintainer's phone (2026-10-26; Samsung SM-G998U, Android 15, agent app
 * 2.16.5 build 208): for a long window every call to the on-device agent failed
 * `UnknownHostException: Unable to resolve host "localhost"` from okhttp's
 * system DNS — getSystemStatus, getMessages, getWAStatus, getLlmConfig,
 * getVerifyStatus, getAuditEntries, getSystemHealth — then recovered. Android
 * does not promise that `localhost` resolves: Private DNS, a VPN and Doze have
 * each been seen to break it. The loopback address needs no lookup at all.
 *
 * Only the TRANSPORT changes. [LOOPBACK_HOST] stays `localhost` on Android for
 * the WebView, whose same-origin policy treats the two names as different
 * origins; an HTTP client has no origin to keep.
 */
const val LOOPBACK_LITERAL = "127.0.0.1"

/** True for a host name that means this device's loopback. */
fun isLoopbackName(host: String): Boolean = host.equals("localhost", ignoreCase = true)

/** [url] with a `localhost` host replaced by [LOOPBACK_LITERAL]; anything else unchanged. */
fun loopbackLiteral(url: String): String =
    Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*://)(?:[^@/]*@)?localhost(?=[:/?#]|$)""", RegexOption.IGNORE_CASE)
        .find(url)?.let { m -> m.groupValues[1] + LOOPBACK_LITERAL + url.substring(m.range.last + 1) } ?: url
