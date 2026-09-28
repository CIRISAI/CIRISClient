package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.models.DeletionProof
import ai.ciris.mobile.shared.models.DeletionSigningKey
import ai.ciris.mobile.shared.models.DeletionVerification
import ai.ciris.mobile.shared.models.ErasureFailure
import ai.ciris.mobile.shared.models.ErasureOutcome
import ai.ciris.mobile.shared.models.ProofParse
import ai.ciris.mobile.shared.models.TraceErasureResult
import ai.ciris.mobile.shared.models.outcome
import ai.ciris.mobile.shared.models.parseDeletionProof
import ai.ciris.mobile.shared.models.proofKeyIsNotCurrent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The erasure and receipt halves of the Data card (FSD/CSD/CSD-039 §3, §6).
 *
 * Not a second screen: the Data card already owns erasure (the lens-trace
 * delete, the account reset, the key wipe). This is the state for the two acts
 * it did not have — erasing an agent's traces ON THIS NODE, and checking a
 * deletion receipt the agent signed — held apart from [DataManagementViewModel]
 * only so it can be tested without a live [CIRISApiClient].
 */
interface DataErasureBackend {
    suspend fun eraseAgentTraces(agentIdHash: String, reason: String): TraceErasureResult
    suspend fun deletionSigningKey(): DeletionSigningKey
    suspend fun deletionPublicKey(keyId: String): String
    suspend fun verifyDeletionProof(proof: DeletionProof): DeletionVerification
}

/** The production backend: node calls to the node, receipt calls to the agent. */
class ApiDataErasureBackend(private val api: CIRISApiClient) : DataErasureBackend {
    override suspend fun eraseAgentTraces(agentIdHash: String, reason: String) = api.eraseAgentTraces(agentIdHash, reason)
    override suspend fun deletionSigningKey() = api.getDeletionSigningKey()
    override suspend fun deletionPublicKey(keyId: String) = api.getDeletionPublicKey(keyId)
    override suspend fun verifyDeletionProof(proof: DeletionProof) = api.verifyDeletionProof(proof)
}

/** The trace-erasure act, from idle to what the node reported. */
sealed interface TraceErasureState {
    data object Idle : TraceErasureState
    data object Working : TraceErasureState

    /** The node answered. [outcome] decides the words; "done" is only [ErasureOutcome.Erased]. */
    data class Answered(val result: TraceErasureResult, val outcome: ErasureOutcome) : TraceErasureState
    data class Failed(val failure: ErasureFailure) : TraceErasureState
}

/** The agent's signing key for receipts. */
sealed interface ReceiptKeyState {
    data object Loading : ReceiptKeyState

    /** [publicKeyB64] is null when the key id read but the `.pub` download did not. */
    data class Loaded(val key: DeletionSigningKey, val publicKeyB64: String?) : ReceiptKeyState
    data class Failed(val failure: ErasureFailure) : ReceiptKeyState
}

/** Checking one receipt. */
sealed interface ReceiptCheckState {
    data object Idle : ReceiptCheckState
    data class Unreadable(val why: String) : ReceiptCheckState
    data class Working(val proof: DeletionProof) : ReceiptCheckState

    /**
     * The agent answered. [keyNotCurrent]: the proof names a key other than the
     * agent's current one, and the agent checks only against the current one — so
     * an invalid verdict here may mean "signed before a rotation", not "forged".
     */
    data class Checked(
        val proof: DeletionProof,
        val verdict: DeletionVerification,
        val keyNotCurrent: Boolean,
    ) : ReceiptCheckState
    data class Failed(val proof: DeletionProof, val failure: ErasureFailure) : ReceiptCheckState
}

class DataErasureController(
    private val backend: DataErasureBackend,
    private val scope: CoroutineScope,
) {
    private val _traceErasure = MutableStateFlow<TraceErasureState>(TraceErasureState.Idle)
    val traceErasure: StateFlow<TraceErasureState> = _traceErasure.asStateFlow()

    private val _receiptKey = MutableStateFlow<ReceiptKeyState>(ReceiptKeyState.Loading)
    val receiptKey: StateFlow<ReceiptKeyState> = _receiptKey.asStateFlow()

    private val _receiptCheck = MutableStateFlow<ReceiptCheckState>(ReceiptCheckState.Idle)
    val receiptCheck: StateFlow<ReceiptCheckState> = _receiptCheck.asStateFlow()

    /**
     * Both fields are mandatory on the node (`federation_admin.rs` ~:505-517), and
     * the node's refusal is the backstop; the button is disabled before that.
     */
    fun canErase(agentIdHash: String, reason: String): Boolean =
        agentIdHash.isNotBlank() && reason.isNotBlank() && _traceErasure.value !is TraceErasureState.Working

    fun eraseAgentTraces(agentIdHash: String, reason: String) {
        if (!canErase(agentIdHash, reason)) return
        _traceErasure.value = TraceErasureState.Working
        scope.launch {
            _traceErasure.value = try {
                val r = backend.eraseAgentTraces(agentIdHash.trim(), reason.trim())
                TraceErasureState.Answered(r, r.outcome())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TraceErasureState.Failed(ErasureFailure.of(e))
            }
        }
    }

    fun clearTraceErasure() {
        if (_traceErasure.value !is TraceErasureState.Working) _traceErasure.value = TraceErasureState.Idle
    }

    /** Read the agent's receipt-signing key. Called only when an agent is attached. */
    fun loadReceiptKey() {
        _receiptKey.value = ReceiptKeyState.Loading
        scope.launch {
            _receiptKey.value = try {
                val key = backend.deletionSigningKey()
                val pub = try {
                    backend.deletionPublicKey(key.publicKeyId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                ReceiptKeyState.Loaded(key, pub)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReceiptKeyState.Failed(ErasureFailure.of(e))
            }
        }
    }

    fun checkReceipt(raw: String) {
        val proof = when (val p = parseDeletionProof(raw)) {
            is ProofParse.Unreadable -> {
                _receiptCheck.value = ReceiptCheckState.Unreadable(p.why)
                return
            }
            is ProofParse.Read -> p.proof
        }
        _receiptCheck.value = ReceiptCheckState.Working(proof)
        val current = (_receiptKey.value as? ReceiptKeyState.Loaded)?.key
        scope.launch {
            _receiptCheck.value = try {
                ReceiptCheckState.Checked(proof, backend.verifyDeletionProof(proof), proofKeyIsNotCurrent(proof, current))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReceiptCheckState.Failed(proof, ErasureFailure.of(e))
            }
        }
    }

    fun clearReceiptCheck() {
        _receiptCheck.value = ReceiptCheckState.Idle
    }
}
