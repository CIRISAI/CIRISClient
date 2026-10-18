package ai.ciris.mobile.shared.ui.shell

import ai.ciris.mobile.shared.Screen
import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ChatApi
import ai.ciris.mobile.shared.models.chat.CegChatMessage
import ai.ciris.mobile.shared.models.chat.ChatCommunity
import ai.ciris.mobile.shared.models.chat.ChatTranscript
import ai.ciris.mobile.shared.models.chat.SendChatMessageResult
import ai.ciris.mobile.shared.platform.TestAutomation
import ai.ciris.mobile.shared.screenHostsComposer
import ai.ciris.mobile.shared.ui.nav.CohortScope
import ai.ciris.mobile.shared.ui.nav.LocalInsideShell
import ai.ciris.mobile.shared.ui.nav.Tab
import ai.ciris.mobile.shared.ui.screens.ChatScreen
import ai.ciris.mobile.shared.ui.theme.LayoutBand
import ai.ciris.mobile.shared.ui.theme.LocalLayoutBand
import ai.ciris.mobile.shared.viewmodels.UserChatViewModel
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ON A PHONE, A CHAT OWNS THE BOTTOM EDGE (CIRISClient#186).
 *
 * iOS, 0.5.228, a physical iPhone: the circle bar rendered under the Interact
 * composer, and with the field focused the keyboard covered what was being
 * typed. Rendered headless at a phone (360 x 640 dp) inside the real shell and
 * read back through the registration a harness sees:
 *
 *  - the circle bar is absent on a chat screen and present on any other;
 *  - with a keyboard inset applied, the composer's bounds lie above it.
 *
 * Desktop's `WindowInsets.ime` is a constant zero, so the keyboard is put in
 * front of the composer through [LocalImeInsets] — the same inset the chat
 * screens pad by on a phone.
 */
class ChatComposerOnPhoneTest {

    private data class Box(val x: Int, val y: Int, val w: Int, val h: Int)

    private val registered = mutableMapOf<String, Box>()
    private val density = 3f
    private val widthPx = (360 * density).toInt()
    private val heightPx = (640 * density).toInt()
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun arm() {
        Dispatchers.setMain(dispatcher)
        TestAutomation.configure(
            onRegister = { tag, x, y, w, h, _ -> registered[tag] = Box(x, y, w, h) },
            onUnregister = { registered.remove(it) },
            onSetScreen = {},
            onClear = { registered.clear() },
            isEnabled = { true },
        )
    }

    @AfterTest
    fun disarm() {
        TestAutomation.configure(
            onRegister = { _, _, _, _, _, _ -> },
            onUnregister = {},
            onSetScreen = {},
            onClear = {},
            isEnabled = { false },
        )
        Dispatchers.resetMain()
    }

    /** A node that holds one room with one message, and records nothing. */
    private object OneRoom : ChatApi {
        override suspend fun open(nodeUrl: String, contactKeyId: String) =
            ChatCommunity(communityId = "room-1", memberKeyIds = listOf("me", contactKeyId))
        override suspend fun transcript(nodeUrl: String, communityId: String) = ChatTranscript(
            communityId = communityId,
            messages = listOf(CegChatMessage(attestationId = "att-1", body = "hi", communityId = communityId)),
            total = 1,
            ready = true,
        )
        override suspend fun send(nodeUrl: String, communityId: String, body: String) =
            SendChatMessageResult(attestationId = "att-2", communityId = communityId)
    }

    private val chatRoute = Screen.UserChat(contactKeyId = null, communityId = "room-1", contactLabel = "Room", memberCount = 3)

    @Composable
    private fun Chat() {
        val vm = androidx.compose.runtime.remember {
            UserChatViewModel(CIRISApiClient(baseUrl = "http://agent.invalid:8080"), { "http://node.invalid:4243" }, OneRoom)
        }
        ChatScreen(viewModel = vm, contactKeyId = null, communityId = "room-1", contactLabel = "Room", onBack = {}, memberCount = 3)
    }

    /** The shell as CIRISApp hosts it on a phone, around [screen]'s content. */
    private fun render(screen: Screen, ime: WindowInsets? = null, content: @Composable () -> Unit): Map<String, Box> {
        registered.clear()
        return ImageComposeScene(width = widthPx, height = heightPx, density = Density(density)) {
            CompositionLocalProvider(
                LocalLayoutBand provides LayoutBand.PHONE,
                LocalImeInsets provides ime,
            ) {
                CirclesShell(
                    circle = CohortScope.FAMILY,
                    tab = Tab.CHATS,
                    hasAgent = false,
                    onBack = {},
                    onCircle = {},
                    onTab = {},
                    onMyThings = {},
                    onStop = {},
                    hostsComposer = screenHostsComposer(screen),
                ) {
                    CompositionLocalProvider(LocalInsideShell provides true) { content() }
                }
            }
        }.use {
            var t = 0L
            repeat(60) { _ -> it.render(t); t += 16_000_000L }
            registered.toMap()
        }
    }

    @Test
    fun theCircleBarIsAbsentOnAChatAndPresentElsewhere() {
        assertTrue(screenHostsComposer(Screen.Interact), "Interact is a chat")
        assertTrue(screenHostsComposer(chatRoute), "a user/pair chat is a chat")
        assertFalse(screenHostsComposer(Screen.Contacts), "Contacts is not a chat")

        val onChat = render(chatRoute) { Chat() }
        assertTrue("input_chat_body" in onChat, "the chat's composer never rendered: ${onChat.keys}")
        assertFalse("shell_bottom_bar" in onChat, "the circle bar is on the chat screen on a phone (CIRISClient#186)")
        assertTrue("shell_top_bar" in onChat, "the top bar must stay on a chat: it is where My things and Stop live")

        val elsewhere = render(Screen.Contacts) { Text("contacts") }
        assertTrue("shell_bottom_bar" in elsewhere, "the circle bar must stay on a screen that is not a chat")
    }

    @Test
    fun withTheKeyboardUpTheComposerSitsAboveIt() {
        val keyboard = 300.dp
        val keyboardTopPx = heightPx - (keyboard.value * density).toInt()
        val seen = render(chatRoute, ime = WindowInsets(bottom = keyboard)) { Chat() }
        val field = assertNotNull(seen["input_chat_body"], "the composer never rendered: ${seen.keys}")
        val send = assertNotNull(seen["btn_chat_send"], "send never rendered: ${seen.keys}")
        for ((tag, b) in listOf("input_chat_body" to field, "btn_chat_send" to send)) {
            assertTrue(
                b.h > 0 && b.y >= 0 && b.y + b.h <= keyboardTopPx,
                "$tag must lie above a ${keyboard.value.toInt()} dp keyboard (its top is at y=$keyboardTopPx px); it is at $b",
            )
        }
        // The newest message is still on screen above the composer.
        val msg = assertNotNull(seen["chat_msg_att-1"], "the transcript's newest message is not on screen: ${seen.keys}")
        assertTrue(msg.y + msg.h <= field.y, "the newest message sits under the composer: $msg vs $field")
    }

    /**
     * Interact pads by the same inset. Its view model is the whole agent client,
     * so it is not composed here; its source is read instead, as
     * `SignOutReachTest` reads CIRISApp's.
     */
    @Test
    fun interactPadsByTheSameKeyboardInsetAndScrollsWhenItOpens() {
        val roots = listOf("src/commonMain/kotlin", "shared/src/commonMain/kotlin", "client/shared/src/commonMain/kotlin")
        val src = roots.map { java.io.File(it, "ai/ciris/mobile/shared/ui/screens/InteractScreen.kt") }.first { it.exists() }.readText()
        assertTrue(".composerImePadding()" in src, "Interact's content does not pad by the composer's keyboard inset")
        assertTrue(Regex("""LaunchedEffect\([^)]*keyboardUp\)""").containsMatchIn(src), "Interact's transcript does not follow the keyboard opening")
    }
}
