package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.models.chat.ChatCommunity
import ai.ciris.mobile.shared.models.chat.ChatMessageHistory
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

    /**
     * `GET {nodeUrl}/v1/chat/{id}/messages/{attestationId}/history`, oldest first;
     * null when the node predates the route (a bare 404).
     */
    suspend fun history(nodeUrl: String, communityId: String, attestationId: String): ChatMessageHistory? = null

    /**
     * `POST {nodeUrl}/v1/chat/{id}/messages/{attestationId}/{op}` — `supersede`
     * (with [body]), `withdraw` or `recant` (with an optional [reason] code).
     * Returns the new row's attestation id.
     */
    suspend fun revise(
        nodeUrl: String,
        communityId: String,
        attestationId: String,
        op: String,
        body: String? = null,
        reason: String? = null,
    ): String = throw UnsupportedOperationException("revise")
}

/** [ChatApi] over the real client, with the client's session. */
class ClientChatApi(private val client: CIRISApiClient) : ChatApi {
    override suspend fun open(nodeUrl: String, contactKeyId: String): ChatCommunity =
        client.startChat(contactKeyId, nodeUrl = nodeUrl)
    override suspend fun transcript(nodeUrl: String, communityId: String): ChatTranscript =
        client.listChatMessages(communityId, nodeUrl = nodeUrl)
    override suspend fun send(nodeUrl: String, communityId: String, body: String): SendChatMessageResult =
        client.sendChatMessage(communityId, body, nodeUrl = nodeUrl)
    override suspend fun history(nodeUrl: String, communityId: String, attestationId: String): ChatMessageHistory? =
        client.getChatMessageHistory(communityId, attestationId, nodeUrl = nodeUrl)
    override suspend fun revise(
        nodeUrl: String,
        communityId: String,
        attestationId: String,
        op: String,
        body: String?,
        reason: String?,
    ): String = client.reviseChatMessage(communityId, attestationId, op, body, reason, nodeUrl = nodeUrl)
}
