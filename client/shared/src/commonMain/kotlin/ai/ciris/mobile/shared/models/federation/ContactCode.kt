package ai.ciris.mobile.shared.models.federation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The person's own **contact code** — `GET {nodeUrl}/v1/self/contact-code?nodes=`
 * (CIRISServer#673, 0.5.218; CSD-092).
 *
 * A v3 fedcode naming the PERSON (their fed-ID key and the commitment to its
 * ML-DSA-65 half) and, by the person's choice, some of their ANNOUNCED devices.
 * With devices it resolves at the adder with no directory; with none it
 * resolves through the public federation directory, which only knows people
 * who announced at least one device.
 *
 * Shape read from the server's `contact_code` handler (`src/self_devices.rs`,
 * CIRISServer 0.5.218). The code is an address, not a credential: it is
 * unsigned (CC 2.6.8(e)) and anyone holding it can use it.
 */
@Serializable
data class ContactCodeResponse(
    /** The person's fed-ID. */
    @SerialName("key_id")
    val keyId: String = "",
    /** The code in its display (grouped) form — shown as text, and what Copy copies. */
    val code: String,
    /** The SAME code, ungrouped (CC 2.6.8: "the QR form is ungrouped"). Drawn into the QR. */
    @SerialName("qr_payload")
    val qrPayload: String? = null,
    val format: String? = null,
    /** The announced devices the person may choose from. Only announced devices appear. */
    @SerialName("available_nodes")
    val availableNodes: List<ContactCodeNode> = emptyList(),
    /** What THIS code carries — read back from the node, not echoed from the picker. */
    @SerialName("included_nodes")
    val includedNodes: List<ContactCodeIncludedNode> = emptyList(),
    /** Chosen and announced, but with no transport binding here to embed (yet). */
    @SerialName("nodes_without_transport")
    val nodesWithoutTransport: List<String> = emptyList(),
    /** Whether a stranger can reach the person from this code alone. */
    @SerialName("reachable_without_directory")
    val reachableWithoutDirectory: Boolean? = null,
) {
    /** The text a QR should carry: the ungrouped form when the node sent it. */
    val qrValue: String get() = qrPayload?.takeIf { it.isNotBlank() } ?: code
}

/** One entry of `available_nodes`: an announced device the person may put in a code. */
@Serializable
data class ContactCodeNode(
    @SerialName("node_key_id")
    val nodeKeyId: String,
    /** The owner's own name for the device, when they gave it one. */
    val label: String? = null,
    val announced: Boolean = true,
    /** False until the device's transport route is bound here (after its next boot). */
    @SerialName("has_transport")
    val hasTransport: Boolean? = null,
    /** This is the node the client is talking to. */
    @SerialName("this_node")
    val thisNode: Boolean = false,
)

/** One entry of `included_nodes`: a device this code actually names. */
@Serializable
data class ContactCodeIncludedNode(
    @SerialName("key_id")
    val keyId: String,
    @SerialName("transport_pubkey_ed25519_base64")
    val transportPubkeyEd25519Base64: String? = null,
)
