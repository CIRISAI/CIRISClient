package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.platform.AttachmentRefusal
import ai.ciris.mobile.shared.platform.PickedFile
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The re-check before send (CIRISClient#217): a type outside
 * [PickedFile.ALLOWED_MIME_TYPES] and a file past [PickedFile.MAX_ATTACHMENTS]
 * are refused with a visible, named refusal — never attached, never dropped
 * with only a log line.
 */
class InteractAttachmentGateTest {

    private val made = mutableListOf<ViewModel>()
    @BeforeTest fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)
    @AfterTest fun tearDown() { stopViewModels(made); Dispatchers.resetMain() }

    private fun vm() = InteractViewModel(CIRISApiClient("http://127.0.0.1:9", "tok")).also { made += it }
    private fun file(name: String, type: String) = PickedFile(name, type, "AAAA", 3)

    @Test
    fun anOctetStreamIsRefusedVisiblyAndNotAttached() {
        val vm = vm()
        vm.addAttachment(file("notes.bin", "application/octet-stream"))
        assertTrue(vm.attachedFiles.value.isEmpty(), "an unsupported type must not be attached")
        assertEquals(AttachmentRefusal.UnsupportedType("notes.bin", "application/octet-stream"), vm.attachmentRefused.value, "the refusal must be visible, not only logged")
    }

    @Test
    fun aHeicIsRefusedVisiblyAndNotAttached() {
        val vm = vm()
        vm.addAttachment(file("IMG_0001.HEIC", "image/heic"))
        assertTrue(vm.attachedFiles.value.isEmpty(), "a HEIC must not be attached")
        assertEquals(AttachmentRefusal.UnsupportedType("IMG_0001.HEIC", "image/heic"), vm.attachmentRefused.value, "the HEIC refusal must be visible")
    }

    @Test
    fun everyAllowedTypeIsAttached() {
        for (t in PickedFile.ALLOWED_MIME_TYPES) {
            val vm = vm()
            vm.addAttachment(file("f", t))
            assertEquals(1, vm.attachedFiles.value.size, t)
            assertNull(vm.attachmentRefused.value, t)
        }
    }

    @Test
    fun oneOverTheCountIsRefusedVisibly() {
        val vm = vm()
        repeat(PickedFile.MAX_ATTACHMENTS) { vm.addAttachment(file("p$it.png", "image/png")) }
        vm.addAttachment(file("extra.png", "image/png"))
        assertEquals(PickedFile.MAX_ATTACHMENTS, vm.attachedFiles.value.size)
        assertEquals(AttachmentRefusal.TooMany("extra.png", PickedFile.MAX_ATTACHMENTS), vm.attachmentRefused.value, "going over the count must say so, not only log it")
    }
}
