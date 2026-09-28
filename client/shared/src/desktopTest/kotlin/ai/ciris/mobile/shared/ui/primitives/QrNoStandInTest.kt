package ai.ciris.mobile.shared.ui.primitives

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * NO QR ON SCREEN THAT DOES NOT SCAN.
 *
 * My Identity drew a Canvas of finder patterns around a hash of the fedcode —
 * it looked like a QR code and scanned as nothing, a false claim on screen.
 * The enrol card now renders [QrCode], whose symbol QrEncoderTest decodes. No
 * Compose UI harness in this module, so this reads the source, the same trade
 * `SignOutReachTest` makes.
 */
class QrNoStandInTest {

    private fun commonMain(): File =
        listOf("src/commonMain/kotlin", "shared/src/commonMain/kotlin", "client/shared/src/commonMain/kotlin")
            .map { File(it) }.firstOrNull { it.isDirectory }
            ?: error("commonMain not found from ${File(".").absolutePath}")

    @Test
    fun theEnrolCardRendersARealQrOfTheFedcode() {
        val screen = File(commonMain(), "ai/ciris/mobile/shared/ui/screens/IdentityManagementScreen.kt").readText()
        val call = Regex("""QrCode\(\s*value = minted\.fedcode,[\s\S]*?tag = "identity_enroll_qr",""")
        assertTrue(call.containsMatchIn(screen), "the enrol card does not render QrCode(minted.fedcode) as identity_enroll_qr")
        assertTrue("FedcodeQr" !in screen, "the stand-in FedcodeQr is back")
    }

    @Test
    fun nothingElseDrawsAQrLookalike() {
        // The stand-in's tells: a hand-built finder pattern on a Canvas.
        val tells = listOf("visual stand-in", "inFinder(", "QR-style matrix")
        val offenders = commonMain().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { t -> tells.any { it in t } } }
            .map { it.name }
            .toList()
        assertTrue(offenders.isEmpty(), "QR lookalikes that do not scan: $offenders")
    }
}
