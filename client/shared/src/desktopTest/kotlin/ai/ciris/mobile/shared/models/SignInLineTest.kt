package ai.ciris.mobile.shared.models

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * "SIGNED IN VIA …" SAYS HOW ONLY WHEN IT IS KNOWN.
 *
 * The provider used to be `getOAuthProviderName()` — the platform's OAuth
 * provider — so a desktop owner who signed in with a password on their own
 * node read "via Google". These render the English sentence exactly as the
 * screens do (key from en.json, `{param}` substituted) and pin that a
 * password sign-in never names Google, and an unrecorded one names nobody.
 */
class SignInLineTest {

    private val en: JsonObject by lazy {
        val f = listOf(
            File("src/desktopMain/resources/localization/en.json"),
            File("shared/src/desktopMain/resources/localization/en.json"),
            File("client/shared/src/desktopMain/resources/localization/en.json"),
        ).firstOrNull { it.exists() } ?: error("en.json not found from ${File(".").absolutePath}")
        Json.parseToJsonElement(f.readText()) as JsonObject
    }

    private fun render(line: SignedInLine): String {
        var node: Any? = en
        for (part in line.key.split('.')) node = (node as? JsonObject)?.get(part)
        val template = (node as? JsonPrimitive)?.content ?: error("${line.key} is not in en.json")
        return line.params.entries.fold(template) { s, (k, v) -> s.replace("{$k}", v) }
    }

    private val id = "ciris-self-1234"

    @Test
    fun aPasswordSignInNeverSaysGoogle() {
        for (line in listOf(signedInLine(id, SignInMethod.PASSWORD), settingsSignInLine(SignInMethod.PASSWORD))) {
            val text = render(line)
            assertFalse("Google" in text, "a password sign-in rendered: $text")
            assertFalse("{" in text, "unfilled placeholder: $text")
        }
        assertTrue("password" in render(signedInLine(id, SignInMethod.PASSWORD)))
    }

    @Test
    fun anUnrecordedSignInSaysWhoButNotHow() {
        val text = render(signedInLine(id, null))
        assertEquals("Signed in on this device as $id", text)
        for (word in listOf("Google", "Apple", "via", "password")) assertFalse(word in text, text)
        val settings = render(settingsSignInLine(null))
        assertFalse("Google" in settings || "Apple" in settings || "{" in settings, settings)
    }

    @Test
    fun aRecordedOAuthSignInNamesItsProvider() {
        assertTrue("via Google" in render(signedInLine(id, SignInMethod.GOOGLE)))
        assertTrue("via Apple" in render(signedInLine(id, SignInMethod.APPLE)))
        assertTrue("Apple" in render(settingsSignInLine(SignInMethod.APPLE)))
    }

    @Test
    fun anUnknownProviderIsUnknownNotGoogle() {
        assertEquals(SignInMethod.GOOGLE, SignInMethod.fromProvider("google"))
        assertEquals(SignInMethod.APPLE, SignInMethod.fromProvider("Apple"))
        assertNull(SignInMethod.fromProvider("github"))
        assertNull(SignInMethod.fromProvider(null))
        assertNull(SignInMethod.fromProvider("password"), "an OAuth flow cannot record a password sign-in")
        assertEquals(SignInMethod.PASSWORD, SignInMethod.fromStored("password"))
    }
}
