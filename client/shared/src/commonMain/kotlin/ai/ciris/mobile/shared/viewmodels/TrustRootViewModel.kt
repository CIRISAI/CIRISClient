package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.models.federation.TrustPosture
import ai.ciris.mobile.shared.models.federation.TrustRootFailure
import ai.ciris.mobile.shared.models.federation.TrustRootImportResult
import ai.ciris.mobile.shared.models.federation.TrustRootListingDto
import ai.ciris.mobile.shared.models.federation.TrustRootUntrustResult
import ai.ciris.mobile.shared.models.federation.TrustRootView
import ai.ciris.mobile.shared.models.federation.UntrustConsequence
import ai.ciris.mobile.shared.models.federation.seedCharterRoot
import ai.ciris.mobile.shared.models.federation.trustPosture
import ai.ciris.mobile.shared.models.federation.trustRootFailure
import ai.ciris.mobile.shared.models.federation.trustRootView
import ai.ciris.mobile.shared.models.federation.untrustConsequence
import ai.ciris.mobile.shared.platform.PlatformLogger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The read of `GET /v1/trust-root`. A failure is never an empty listing. */
sealed interface TrustRootRead {
    data object Loading : TrustRootRead
    data class Loaded(val posture: TrustPosture, val roots: List<TrustRootView>) : TrustRootRead
    data class Failed(val failure: TrustRootFailure) : TrustRootRead
}

/** The accord family's version chain, on the Accord card. */
sealed interface FamilyHistoryState {
    data object Loading : FamilyHistoryState
    data class Loaded(val versions: List<ai.ciris.mobile.shared.models.federation.FamilyVersionView>) : FamilyHistoryState
    data class Failed(val failure: TrustRootFailure) : FamilyHistoryState
}

/** Adopting a seed: parse locally, confirm three facts, send, report the two acts apart. */
sealed interface ImportStage {
    data object Idle : ImportStage
    /** The text is not a JSON object, so nothing was sent. */
    data object NotJson : ImportStage
    /** Parsed; waiting on the ConfirmSheet. [charterRoot] is null when the file names none this app can read. */
    data class Confirming(val bundle: JsonElement, val charterRoot: String?, val familyKeyId: String?) : ImportStage
    data object Sending : ImportStage
    data class Done(val result: TrustRootImportResult, val charterRoot: String?) : ImportStage
    data class Failed(val failure: TrustRootFailure) : ImportStage
}

/** Un-trusting one root. */
sealed interface UntrustStage {
    data object Idle : UntrustStage
    data class Confirming(val root: TrustRootView, val consequence: UntrustConsequence) : UntrustStage
    data class Sending(val rootKeyId: String) : UntrustStage
    data class Done(val result: TrustRootUntrustResult) : UntrustStage
    data class Failed(val rootKeyId: String, val failure: TrustRootFailure) : UntrustStage
}

/**
 * Drives **this node's trust root** — the detail reached from the Accord card.
 *
 * The accord family is the default trust root (`GET /v1/trust-root` lists
 * `humanity-accord` first); this is the node's side of it — does its own
 * acceptance reach the root, is the root sound — and the two CC 3.2 levers:
 * adopt a portable seed (`POST /v1/trust-root/import`) and withdraw an
 * acceptance (`DELETE /v1/trust-root/{id}`). All three are loopback-only
 * (CIRISServer#652); off the node's machine every one of them reads as
 * [TrustRootFailure.LoopbackOnly], never as "no roots".
 *
 * Both writes go behind a ConfirmSheet the screen draws from [ImportStage.Confirming]
 * and [UntrustStage.Confirming]; nothing is sent until the person confirms.
 */
class TrustRootViewModel(
    private val apiClient: CIRISApiClient,
) : ViewModel() {

    companion object {
        private const val TAG = "TrustRootVM"
        private val lenient = Json { ignoreUnknownKeys = true }

        /** Pure: the pasted/loaded text as a seed ready to confirm, or [ImportStage.NotJson]. */
        fun prepareImport(text: String): ImportStage {
            val element = try {
                lenient.parseToJsonElement(text.trim())
            } catch (_: Exception) {
                return ImportStage.NotJson
            }
            val obj = element as? JsonObject ?: return ImportStage.NotJson
            val family = (obj["family_key_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotBlank() }
            return ImportStage.Confirming(element, seedCharterRoot(element), family)
        }
    }

    private val _read = MutableStateFlow<TrustRootRead>(TrustRootRead.Loading)
    val read: StateFlow<TrustRootRead> = _read.asStateFlow()

    private val _importStage = MutableStateFlow<ImportStage>(ImportStage.Idle)
    val importStage: StateFlow<ImportStage> = _importStage.asStateFlow()

    private val _untrustStage = MutableStateFlow<UntrustStage>(UntrustStage.Idle)
    val untrustStage: StateFlow<UntrustStage> = _untrustStage.asStateFlow()

    fun refresh() {
        _read.value = TrustRootRead.Loading
        viewModelScope.launch {
            _read.value = try {
                loaded(apiClient.getTrustRoots())
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "[refresh] ${e.message}")
                TrustRootRead.Failed(trustRootFailure(e))
            }
        }
    }

    private fun loaded(listing: TrustRootListingDto) =
        TrustRootRead.Loaded(listing.trustPosture(), listing.roots.map(::trustRootView))

    // ── Adopt a seed ────────────────────────────────────────────────────────

    /** Parse the seed and open the confirm. Nothing is sent. */
    fun beginImport(text: String) {
        _importStage.value = prepareImport(text)
    }

    fun cancelImport() {
        _importStage.value = ImportStage.Idle
    }

    /** The person confirmed: send the seed verbatim. */
    fun confirmImport(allegianceFrom: String?) {
        val c = _importStage.value as? ImportStage.Confirming ?: return
        _importStage.value = ImportStage.Sending
        viewModelScope.launch {
            _importStage.value = try {
                val res = apiClient.importTrustRoot(c.bundle, allegianceFrom)
                ImportStage.Done(res, c.charterRoot)
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "[confirmImport] ${e.message}")
                ImportStage.Failed(trustRootFailure(e))
            }
            refresh()
        }
    }

    // ── Un-trust a root ─────────────────────────────────────────────────────

    fun beginUntrust(root: TrustRootView) {
        val roots = (_read.value as? TrustRootRead.Loaded)?.roots.orEmpty()
        _untrustStage.value = UntrustStage.Confirming(root, untrustConsequence(roots, root.rootKeyId))
    }

    fun cancelUntrust() {
        _untrustStage.value = UntrustStage.Idle
    }

    fun confirmUntrust() {
        val c = _untrustStage.value as? UntrustStage.Confirming ?: return
        val id = c.root.rootKeyId
        _untrustStage.value = UntrustStage.Sending(id)
        viewModelScope.launch {
            _untrustStage.value = try {
                UntrustStage.Done(apiClient.untrustRoot(id))
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "[confirmUntrust] ${e.message}")
                UntrustStage.Failed(id, trustRootFailure(e))
            }
            refresh()
        }
    }

    fun dismissResults() {
        if (_importStage.value !is ImportStage.Sending && _importStage.value !is ImportStage.Confirming) {
            _importStage.value = ImportStage.Idle
        }
        if (_untrustStage.value !is UntrustStage.Sending && _untrustStage.value !is UntrustStage.Confirming) {
            _untrustStage.value = UntrustStage.Idle
        }
    }
}
