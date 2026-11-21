package ai.ciris.mobile.shared.models.federation

import ai.ciris.mobile.shared.models.capability.NodeEdition

/**
 * WHAT THE ACCORD CARD OFFERS ON WHICH MESH (CSD-067 §4, CSD-003, CSD-069).
 *
 * v62 is a total re-genesis, and it retires the 0.5.x acts below: admitting a
 * node, proposing a canonical, blessing CI workers (deferred to v62.1, held),
 * re-minting or re-scrubbing the root, co-scrub pasting, conferring a duty from
 * the accord (duties come from the trust root), the canonical withdraw and
 * supersede doors (deleted: only a later bundle un-seats a canonical), and the
 * in-app 0.5.x ceremonies (the v62 genesis is the offline ceremony tool, with no
 * HTTP route). Holder provisioning is CEREMONY step 1, offline, on v62.
 *
 * On [NodeEdition.EDITION_1] every act is offered exactly as before. On
 * [NodeEdition.EDITION_2] and on [NodeEdition.UNKNOWN] the retired acts are not
 * offered: an unknown node is not asked to do something only one edition admits.
 * Drill, announce and halt are on both editions; their v62 route shapes are
 * 0.6: pending (CIRISServer), so they stay wired to the 0.5.x routes until then.
 */
object AccordEditionGate {
    /** `[+ New]` ids that exist only on the earlier mesh. */
    val RETIRED_NEW_ACTIONS: Set<String> = setOf(
        "admit_node", "add_canonical", "bless_ci_workers", "remint_trust_root",
        "rescrub_trust_root", "cosign_paste", "found_accord", "confer_duty",
    )

    /** Whether `[+ New]` offers [id] on a node of [edition]. */
    fun offersNewAction(id: String, edition: NodeEdition): Boolean =
        edition == NodeEdition.EDITION_1 || id !in RETIRED_NEW_ACTIONS

    /** The canonical rows' withdraw and supersede doors (deleted on v62). */
    fun offersCanonicalDoors(edition: NodeEdition): Boolean = edition == NodeEdition.EDITION_1

    /**
     * The in-app 0.5.x ceremonies: the family-envelope genesis (CSD-069), the
     * final genesis and its amend (CSD-067), and holder provisioning (CSD-068).
     */
    fun offersInAppCeremony(edition: NodeEdition): Boolean = edition == NodeEdition.EDITION_1

    /**
     * The 0.5.x-only reads the card makes on entry (canonical roster, gossiped
     * co-scrubs). Not asked of a node that would only answer that they are gone.
     */
    fun readsEarlierMeshRoster(edition: NodeEdition): Boolean = edition == NodeEdition.EDITION_1

    /** The note drawn instead of the retired acts, or null when none is due. */
    fun noteKey(edition: NodeEdition): String? = when (edition) {
        NodeEdition.EDITION_1 -> null
        NodeEdition.EDITION_2 -> "mobile.accord_edition2_offline_ceremony"
        NodeEdition.UNKNOWN -> "mobile.accord_edition_unknown"
    }
}
