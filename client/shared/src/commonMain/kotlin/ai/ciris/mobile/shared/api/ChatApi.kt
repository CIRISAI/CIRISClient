package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.models.chat.ChatCommunity
import ai.ciris.mobile.shared.models.chat.ChatTranscript
import ai.ciris.mobile.shared.models.chat.SendChatMessageResult

/**
 * What the chat screen asks a node (CSD-091).
 *
 * An interface of its own, like [ContactsApi], so the view model can be driven
 * by a fake that records WHERE each call went: every route here is the NODE's
 * (`src/contacts_chat.rs`), and on a with-AI install the client's api base is
 * the agent (CIRISAgent#1213). The caller names the node.
 *
 * Refusals throw [NodeRefusal] with the server's id.
 */
interface ChatApi {
    /**
     * `POST {nodeUrl}/v1/chat {key_id}` — get-or-create the PAIR room with a
     * contact. Pair-only by construction: an N-member room is never opened
     * here, it is entered by its id through [transcript] (CIRISServer#594).
     */
    suspend fun open(nodeUrl: String, contactKeyId: String): ChatCommunity

    /** `GET {nodeUrl}/v1/chat/{id}/messages` — a pair room or an N-member room, by id. */
    suspend fun transcript(nodeUrl: String, communityId: String): ChatTranscript

    /** `POST {nodeUrl}/v1/chat/{id}/messages`. */
    suspend fun send(nodeUrl: String, communityId: String, body: String): SendChatMessageResult
}

/** [ChatApi] over the real client, with the client's session. */
class ClientChatApi(private val client: CIRISApiClient) : ChatApi {
    override suspend fun open(nodeUrl: String, contactKeyId: String): ChatCommunity =
        client.startChat(contactKeyId, nodeUrl = nodeUrl)
    override suspend fun transcript(nodeUrl: String, communityId: String): ChatTranscript =
        client.listChatMessages(communityId, nodeUrl = nodeUrl)
    override suspend fun send(nodeUrl: String, communityId: String, body: String): SendChatMessageResult =
        client.sendChatMessage(communityId, body, nodeUrl = nodeUrl)
}
