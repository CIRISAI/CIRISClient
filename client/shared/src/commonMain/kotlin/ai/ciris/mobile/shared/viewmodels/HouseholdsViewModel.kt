package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.HouseholdsApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.federation.Contact
import ai.ciris.mobile.shared.models.federation.FamilyChangeCarry
import ai.ciris.mobile.shared.models.federation.FamilyDto
import ai.ciris.mobile.shared.models.federation.FamilySignatureDto
import ai.ciris.mobile.shared.platform.PlatformLogger
import ai.ciris.mobile.shared.ui.screens.ActRoute
import ai.ciris.mobile.shared.ui.screens.Governance
import ai.ciris.mobile.shared.ui.screens.HouseholdAct
import ai.ciris.mobile.shared.ui.screens.ProtocolChoice
import ai.ciris.mobile.shared.ui.screens.ReadFailure
import ai.ciris.mobile.shared.ui.screens.action
import ai.ciris.mobile.shared.ui.screens.familyId
import ai.ciris.mobile.shared.ui.screens.governanceOf
import ai.ciris.mobile.shared.ui.screens.routeOf
import ai.ciris.mobile.shared.ui.screens.targetKeyId
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Where the household list stands. Error and empty never look alike: an empty list is [Loaded] with none. */
sealed interface HouseholdsLoad {
    data object Loading : HouseholdsLoad
    data class Loaded(val families: List<FamilyDto>) : HouseholdsLoad
    /** The read failed. [refusal] is the node's typed answer when it gave one (e.g. `family.owner_session_required`). */
    data class Failed(val failure: ReadFailure, val refusal: NodeRefusal? = null) : HouseholdsLoad
}

/**
 * A change to a quorum household that is waiting for signatures (CSD-100 §2).
 *
 * The node keeps no pending changes: the envelope travels with whoever carries
 * it and each member signs on their OWN node (`family_api.rs:1219-1231`). So
 * this lives here, in memory, for as long as the person is carrying it — and
 * leaves with them as the text [ai.ciris.mobile.shared.ui.screens.encodeCarry] makes.
 */
data class PendingChange(
    val familyId: String,
    val action: String,
    val targetKeyId: String?,
    val envelope: JsonObject,
    /** Every active member: who may sign, in the node's order. */
    val signers: List<String>,
    val required: Int,
    val signatures: List<FamilySignatureDto>,
    /** The node said enough have signed (from the last cosign). */
    val quorumMet: Boolean,
    /** Who proposed it, when this device did; null for a change pasted in from someone else. */
    val proposedBy: String?,
) {
    val carry: FamilyChangeCarry get() = FamilyChangeCarry(envelope, signatures)
    fun signedBy(keyId: String?): Boolean = keyId != null && signatures.any { it.memberId == keyId }
}

/** What just happened, said once on screen. */
enum class HouseholdNotice { CREATED, ADDED, REMOVED, ROLE_CHANGED, LEFT, DISSOLVED, PROPOSED, SIGNED, APPLIED }

/**
 * Drives both household cards: the household in the Family hub (CSD-100,
 * Family › Rules) and its roster (CSD-101, Family › People). One instance, so
 * the household you picked in one is the household you see in the other.
 *
 * The node decides everything that matters — membership is its fold, and it
 * refuses any change the family's protocol does not authorize. This view
 * model decides only what to OFFER ([routeOf]): a founder_only family's
 * founder gets one call, a quorum family gets a proposal others must sign,
 * and anyone else gets the sentence that says why not.
 */
class HouseholdsViewModel(private val api: HouseholdsApi) : ViewModel() {

    companion object {
        private const val TAG = "HouseholdsVM"
        /** A person in more than this many households is unusual; the pager stops rather than looping on a bad `resume`. */
        private const val MAX_PAGES = 20
    }

    private val _load = MutableStateFlow<HouseholdsLoad>(HouseholdsLoad.Loading)
    val load: StateFlow<HouseholdsLoad> = _load.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    /** The people a member can be added from. A failed read leaves it empty and sets [contactsFailed]. */
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()
    private val _contactsFailed = MutableStateFlow(false)
    val contactsFailed: StateFlow<Boolean> = _contactsFailed.asStateFlow()

    /** The owner's own key, so the roster can say "you". Null when the node would not say. */
    private val _myKeyId = MutableStateFlow<String?>(null)
    val myKeyId: StateFlow<String?> = _myKeyId.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** The node's refusal of the last write, rendered by its `family.*` id. */
    private val _refusal = MutableStateFlow<NodeRefusal?>(null)
    val refusal: StateFlow<NodeRefusal?> = _refusal.asStateFlow()

    private val _notice = MutableStateFlow<HouseholdNotice?>(null)
    val notice: StateFlow<HouseholdNotice?> = _notice.asStateFlow()

    /** The act waiting on its three-fact confirm, or null. */
    private val _confirming = MutableStateFlow<HouseholdAct?>(null)
    val confirming: StateFlow<HouseholdAct?> = _confirming.asStateFlow()

    private val _pending = MutableStateFlow<PendingChange?>(null)
    val pending: StateFlow<PendingChange?> = _pending.asStateFlow()

    /** The household being looked at: the selected one, else the first. */
    fun selected(): FamilyDto? {
        val families = (_load.value as? HouseholdsLoad.Loaded)?.families ?: return null
        return families.firstOrNull { it.familyId == _selectedId.value } ?: families.firstOrNull()
    }

    fun governance(family: FamilyDto): Governance = governanceOf(family.consensusProtocol, family.myRole)

    fun load() {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        if (_load.value !is HouseholdsLoad.Loaded) _load.value = HouseholdsLoad.Loading
        _load.value = try {
            val all = mutableListOf<FamilyDto>()
            var after: String? = null
            var pages = 0
            do {
                val page = api.listFamilies(after)
                all += page.families
                after = page.resume
                pages++
            } while (after != null && pages < MAX_PAGES)
            HouseholdsLoad.Loaded(all)
        } catch (e: NodeRefusal) {
            PlatformLogger.w(TAG, "[load] refused reason_id=${e.reasonId ?: "<none>"} status=${e.statusCode}")
            // A bare 404 (no id) is a node without the routes; anything else is a
            // read the node answered with a reason, and the reason is the message.
            if (e.statusCode == 404 && e.reasonId == null) HouseholdsLoad.Failed(ReadFailure.NotOnThisNode(e.message))
            else HouseholdsLoad.Failed(ReadFailure.Failed(e.detail ?: e.reasonId), e)
        } catch (e: Exception) {
            PlatformLogger.w(TAG, "[load] failed: ${e.message}")
            HouseholdsLoad.Failed(ReadFailure.of(e))
        }
        val families = (_load.value as? HouseholdsLoad.Loaded)?.families.orEmpty()
        if (families.none { it.familyId == _selectedId.value }) _selectedId.value = families.firstOrNull()?.familyId
        try {
            _contacts.value = api.contacts()
            _contactsFailed.value = false
        } catch (e: Exception) {
            PlatformLogger.w(TAG, "[load] contacts unreadable: ${e.message}")
            _contactsFailed.value = true
        }
        if (_myKeyId.value == null) {
            _myKeyId.value = try { api.myKeyId() } catch (e: Exception) { null }
        }
    }

    fun select(familyId: String) {
        _selectedId.value = familyId
        _refusal.value = null
        _notice.value = null
    }

    fun clearMessages() {
        _refusal.value = null
        _notice.value = null
    }

    // ── Forming a household ─────────────────────────────────────────────────

    /** Returns through state: on success the new household is selected and [notice] is CREATED. */
    fun create(name: String, protocol: ProtocolChoice, founding: List<String>) {
        run(HouseholdNotice.CREATED) {
            val made = api.createFamily(
                name = name.trim(),
                consensusProtocol = protocol.wire.takeIf { protocol != ProtocolChoice.FOUNDER_ONLY },
                members = founding,
            )
            _selectedId.value = made.familyId
        }
    }

    // ── Acts: confirm first, then the route the protocol allows ────────────

    fun request(act: HouseholdAct) {
        _refusal.value = null
        _notice.value = null
        _confirming.value = act
    }

    fun cancelConfirm() {
        _confirming.value = null
    }

    /**
     * The hub's confirm (CSD-100): leave or dissolve, sent the way this
     * household's protocol allows. A roster act waiting here sends nothing —
     * it has no control on the hub, so the hub has no door to it.
     */
    fun confirmHouseholdAct() {
        val act = _confirming.value as? HouseholdAct.OfHousehold
        if (act == null) {
            PlatformLogger.w(TAG, "[confirmHouseholdAct] ${_confirming.value} is not the hub's act; nothing sent")
            return
        }
        _confirming.value = null
        val family = selected() ?: return
        when (routeOf(act, governance(family))) {
            ActRoute.DIRECT -> run(if (act == HouseholdAct.Leave) HouseholdNotice.LEFT else HouseholdNotice.DISSOLVED) {
                when (act) {
                    HouseholdAct.Dissolve -> api.dissolveFamily(family.familyId)
                    HouseholdAct.Leave -> api.leave(family.familyId)
                }
            }
            ActRoute.PROPOSE -> propose(family, act, null, null)
            ActRoute.NOT_ALLOWED -> PlatformLogger.w(TAG, "[confirmHouseholdAct] $act is not this person's to make; nothing sent")
        }
    }

    /**
     * The roster's confirm (CSD-101): add, remove or change a role, sent the
     * way this household's protocol allows. A hub act waiting here sends
     * nothing — leaving and dissolving have no control on the roster.
     */
    fun confirmMemberAct() {
        val act = _confirming.value as? HouseholdAct.OfMember
        if (act == null) {
            PlatformLogger.w(TAG, "[confirmMemberAct] ${_confirming.value} is not the roster's act; nothing sent")
            return
        }
        _confirming.value = null
        val family = selected() ?: return
        when (routeOf(act, governance(family))) {
            ActRoute.DIRECT -> {
                val notice = when (act) {
                    is HouseholdAct.Add -> HouseholdNotice.ADDED
                    is HouseholdAct.Remove -> HouseholdNotice.REMOVED
                    is HouseholdAct.Role -> HouseholdNotice.ROLE_CHANGED
                }
                run(notice) {
                    when (act) {
                        is HouseholdAct.Add -> api.addMember(family.familyId, act.keyId, null)
                        is HouseholdAct.Remove -> api.removeMember(family.familyId, act.keyId)
                        is HouseholdAct.Role -> api.changeRole(family.familyId, act.keyId, act.role)
                    }
                }
            }
            ActRoute.PROPOSE -> propose(family, act, act.keyId, (act as? HouseholdAct.Role)?.role)
            ActRoute.NOT_ALLOWED -> PlatformLogger.w(TAG, "[confirmMemberAct] $act is not this person's to make; nothing sent")
        }
    }

    /**
     * A quorum household's change, proposed for others to sign. The one route
     * both screens reach, by design: a quorum dissolve is proposed from the hub,
     * a quorum add / remove / role from the roster (CSD-100 §3.1).
     */
    private fun propose(family: FamilyDto, act: HouseholdAct, key: String?, role: String?) {
        val action = act.envelopeAction ?: return
        run(HouseholdNotice.PROPOSED, reloadAfter = false) {
            val p = api.proposeChange(family.familyId, action, key, role)
            _pending.value = PendingChange(
                familyId = family.familyId,
                action = action,
                targetKeyId = key,
                envelope = p.changeEnvelope,
                signers = p.signers,
                required = p.requiredSignatures,
                signatures = emptyList(),
                quorumMet = false,
                proposedBy = _myKeyId.value,
            )
        }
    }

    // ── The ceremony: sign, carry, apply ────────────────────────────────────

    /** This node's owner signs the pending change (their own pen, on their own node). */
    fun sign() {
        val p = _pending.value ?: return
        run(HouseholdNotice.SIGNED, reloadAfter = false) {
            val r = api.cosign(p.familyId, p.envelope, p.signatures)
            _pending.value = p.copy(
                signatures = r.signatures,
                required = r.requiredSignatures.takeIf { it > 0 } ?: p.required,
                quorumMet = r.quorumMet,
            )
        }
    }

    /** Apply the change once enough members have signed. The node verifies the quorum, not this. */
    fun apply() {
        val p = _pending.value ?: return
        run(HouseholdNotice.APPLIED) {
            api.assemble(p.familyId, p.envelope, p.signatures)
            _pending.value = null
        }
    }

    fun discardChange() {
        _pending.value = null
    }

    /**
     * Take in a change someone else carried here. Returns false (and changes
     * nothing) for text that is not a change, or one for a household this
     * person is not in on this node.
     */
    fun importChange(carry: FamilyChangeCarry?): Boolean {
        carry ?: return false
        val familyId = carry.familyId() ?: return false
        val families = (_load.value as? HouseholdsLoad.Loaded)?.families ?: return false
        val family = families.firstOrNull { it.familyId == familyId } ?: return false
        val required = (governance(family) as? Governance.Quorum)?.m ?: return false
        _selectedId.value = familyId
        _pending.value = PendingChange(
            familyId = familyId,
            action = carry.action() ?: return false,
            targetKeyId = carry.targetKeyId(),
            envelope = carry.changeEnvelope,
            signers = family.members.map { it.keyId },
            required = required,
            signatures = carry.signatures,
            quorumMet = false,
            proposedBy = null,
        )
        _refusal.value = null
        return true
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private fun run(notice: HouseholdNotice, reloadAfter: Boolean = true, block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _refusal.value = null
        _notice.value = null
        viewModelScope.launch {
            try {
                block()
                _notice.value = notice
                if (reloadAfter) reload()
            } catch (e: NodeRefusal) {
                PlatformLogger.w(TAG, "[$notice] refused reason_id=${e.reasonId ?: "<none>"} status=${e.statusCode}")
                _refusal.value = e
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "[$notice] failed: ${e.message}")
                _refusal.value = NodeRefusal(null, e.message, 0)
            } finally {
                _busy.value = false
            }
        }
    }

    /** Owner-gated content: nothing of it survives into the next session. */
    fun clearSessionState() {
        _load.value = HouseholdsLoad.Loading
        _selectedId.value = null
        _contacts.value = emptyList()
        _myKeyId.value = null
        _pending.value = null
        _confirming.value = null
        clearMessages()
    }
}
