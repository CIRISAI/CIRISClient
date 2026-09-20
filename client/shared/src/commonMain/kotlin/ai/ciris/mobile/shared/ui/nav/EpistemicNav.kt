package ai.ciris.mobile.shared.ui.nav

import androidx.compose.ui.graphics.vector.ImageVector
import ai.ciris.mobile.shared.ui.components.CIRISIcons

/**
 * The navigable surfaces — the screens the app can show.
 *
 * This file is the INVENTORY. The one tree that places each surface in a
 * circle and a tab, or under an instrument, is `CirclesNav.kt` (the locked
 * spec, wave 1). The six collapsible rail groups, the parent/child chevrons
 * and the SOON gate that used to live here are gone with the shell they
 * belonged to.
 *
 * Origin: Figma Make `oC84aP8FdamRjISS5UPvz3` (issue #799), CIRISAgent#800.
 *
 * **Iconography**: every surface uses a `CIRISIcons.*` entry (the CIRIS
 * brand icon set from `ui/components/CIRISIcons.kt`). No Material icons are
 * referenced here — the CIRIS palette is the brand surface and Material
 * icons would re-introduce the "looks like Google" aesthetic the bus palette
 * was specifically designed to avoid (see `CIRISColors` doc on
 * non-uniform hue distribution).
 *
 * A surface's id is its test-tag stem: `nav_epistemic_<id with - as _>`.
 */

/**
 * A single navigable surface: a screen the app can show. WHERE it lives —
 * which circle and tab, or which instrument under My things — is the one
 * tree in `CirclesNav.kt`. A surface no longer carries a group, a parent or a
 * gate: the two independent axes that put Config under one group while it
 * hung off a parent in another are gone, and so is the SOON badge (every
 * surface here is fully built and reachable — the locked spec's no-gating rule).
 */
sealed class NavSurface(
    val id: String,
    /**
     * English fallback label. Used as the sidebar display when [labelKey] is
     * null OR the localizer has no entry for that key. Always provide a
     * sensible English string — the localizer is best-effort.
     */
    val label: String,
    val icon: ImageVector,
    /**
     * Optional localization key for the sidebar / card title. When set the
     * sidebar resolves `localizedString(labelKey)` and falls back to [label]
     * if the locale lacks an entry. Per 2.9.4 release-prep: every NavSurface
     * that ships a Commons-group / Federation-group card carries a labelKey
     * so the 28-locale fanout lands cleanly. Other surfaces stay on raw
     * [label] until they're touched.
     */
    val labelKey: String? = null,
) {
    // ═══════════════════════════════════════════════════════════════════════════
    // Agent group — runtime interaction surfaces
    // ═══════════════════════════════════════════════════════════════════════════

    object Sessions : NavSurface("sessions", "Sessions", CIRISIcons.dateRange,
        labelKey = "nav.surface.sessions")
    object Interact : NavSurface(
        id = "interact", label = "Interact", icon = CIRISIcons.thought,
        labelKey = "nav.surface.interact")

    object Scheduler : NavSurface("scheduler", "Scheduler", CIRISIcons.stage,
        labelKey = "nav.surface.scheduler")
    object Tickets : NavSurface(
        id = "tickets", label = "Tickets", icon = CIRISIcons.task,
        labelKey = "nav.surface.tickets")

    object Tools : NavSurface("tools", "Tools", CIRISIcons.tools,
        labelKey = "nav.surface.tools")
    // Node form: Services is a node-infra keeper; the agent-only Tools child is
    // dropped from the surfaced tree (the Tools object remains defined for
    // route compatibility).
    object Services : NavSurface(
        id = "services", label = "Services", icon = CIRISIcons.bus,
        labelKey = "nav.surface.services")

    object Logs : NavSurface("logs", "Logs", CIRISIcons.log,
        labelKey = "nav.surface.logs")

    /**
     * Transport — node transports + serial LoRa (RNode) radio configuration. A
     * node-infra surface: shows the node's current Reticulum transport/peers
     * (read-only) and an owner-gated radio config form persisting `net.radio.*`
     * config. Radio activation is desktop-only. Live (no gate).
     */
    object Transport : NavSurface("transport", "Transport", CIRISIcons.bus,
        labelKey = "nav.surface.transport")
    object Telemetry : NavSurface(
        id = "telemetry", label = "Telemetry", icon = CIRISIcons.telemetry,
        labelKey = "nav.surface.telemetry")

    object GraphMemory : NavSurface("graph-memory", "Graph", CIRISIcons.graph,
        labelKey = "nav.surface.graph_memory")
    object Memory : NavSurface(
        id = "memory", label = "Memory", icon = CIRISIcons.memory,
        labelKey = "nav.surface.memory")

    object WiseAuthority : NavSurface("wise-authority", "Wise Authority", CIRISIcons.agent,
        labelKey = "nav.surface.wise_authority")

    // Settings sub-tree — collects LLM / System / Runtime / Config / Skills
    object LLMSettings : NavSurface("llm-settings", "LLM", CIRISIcons.model,
        labelKey = "nav.surface.llm_settings")
    object System : NavSurface("system", "System", CIRISIcons.requirements,
        labelKey = "nav.surface.system")
    object Runtime : NavSurface("runtime", "Runtime", CIRISIcons.processing,
        labelKey = "nav.surface.runtime")
    object Config : NavSurface("config", "Config", CIRISIcons.instructions,
        labelKey = "nav.surface.config")
    object Skills : NavSurface("skills", "Skills", CIRISIcons.skill,
        labelKey = "nav.surface.skills")
    object AgentSettings : NavSurface(
        id = "agent-settings", label = "Settings", icon = CIRISIcons.settings,
        labelKey = "nav.surface.agent_settings")

    /**
     * Settings, reachable WITHOUT a brain (CIRISClient#51).
     *
     * `Screen.Settings` carries `btn_logout`, and on a node install nothing
     * reached it: [AgentSettings] lives in AGENT_GROUP, which is dropped when
     * `hasAgent = false`, and the governance menu that also offers logout is in
     * `CIRISTopBar`, which only the agent home renders. So a run-without-AI
     * owner could not sign out on ANY platform — and because the device-reset
     * affordance lives in the Login footer, could not factory-reset either.
     *
     * The desktops appeared to escape this until 0.5.215 only because a stale
     * `clientMode=AGENT` was landing them on Interact by accident (#48).
     *
     * CHILDLESS ON PURPOSE. [AgentSettings]'s children — LLM, System, Runtime,
     * Config, Skills — are agent configuration and have nothing to configure on
     * a bare node. This surface is the account, not the agent.
     *
     * PRESENT IN BOTH MODES, and that is not incidental — `narrowingIsPurely-
     * Subtractive` pins that the node nav is a SUBSET of the agent nav, so a
     * surface that appears only when narrowed is a defect by this repo's own
     * rule. The first cut of this fix added it on the node build alone and that
     * test caught it. Labelled "Account" rather than "Settings" so the agent
     * build, which also offers [AgentSettings], does not show two identically
     * named rows routing to the same screen.
     *
     * Reuses `mobile.settings_account`, which is already "Account" in all 29
     * bundles — a new key would need a value in each, kept at parity by a check.
     */
    object Account : NavSurface(
        id = "account", label = "Account", icon = CIRISIcons.person,
        labelKey = "mobile.settings_account")

    // ═══════════════════════════════════════════════════════════════════════════
    // Manage group — operator surfaces
    // ═══════════════════════════════════════════════════════════════════════════

    // Health & Reputation ships in 2.9.4 with local + fleet capacity score
    // (data: InteractViewModel.cellVizState ← /v1/my-data/capacity).
    // The federation-attestations sub-section inside the card retains the
    // LENSCORE_CAPACITY gate; the surface itself is not gated.
    object HealthReputation : NavSurface(
        id = "health-reputation", label = "Health & Reputation", icon = CIRISIcons.identity,
        labelKey = "nav.surface.health_reputation")
    object Users : NavSurface("users", "Users", CIRISIcons.person,
        labelKey = "nav.surface.users")
    object Adapters : NavSurface("adapters", "Adapters", CIRISIcons.adapter,
        labelKey = "nav.surface.adapters")
    /**
     * Network (CIRISEdge operator view) — THIS node's local edge facts:
     * federation signer_key_id, agent mode (client/proxy/server), disk budget,
     * data dir. Distinct from the Commons → Global Commons federation/peers
     * SOCIAL view; this is the operator-infra slice. Live (no gate).
     */
    object NetworkOps : NavSurface("network-ops", "Network", CIRISIcons.bus,
        labelKey = "nav.surface.network_ops")

    /**
     * Storage (CIRISPersist operator view) — the graph store + on-disk facts:
     * total nodes, nodes by type/scope, recent activity, storage location.
     * Live (no gate).
     */
    object Storage : NavSurface("storage", "Storage", CIRISIcons.pkg,
        labelKey = "nav.surface.storage")

    object Audit : NavSurface("audit", "Audit", CIRISIcons.audit,
        labelKey = "nav.surface.audit")
    object Consent : NavSurface("consent", "Consent", CIRISIcons.lock,
        labelKey = "nav.surface.consent")
    object Data : NavSurface(
        id = "data", label = "Data", icon = CIRISIcons.pkg,
        labelKey = "nav.surface.data")

    object Trust : NavSurface("trust", "Trust", CIRISIcons.shield,
        labelKey = "nav.surface.trust")

    /**
     * Nodes — the first-class node-management surface (promoted from the
     * in-page node-switcher dropdown). Lists/adds/edits/removes the saved fabric
     * [ai.ciris.mobile.shared.models.NodeProfile]s and switches the active node.
     * Live (no gate) — it manages locally-held profiles.
     */
    object Nodes : NavSurface("nodes", "Nodes", CIRISIcons.bus,
        labelKey = "nav.surface.nodes")

    /**
     * Manage Consent — view + manage the consent objects this device holds
     * (bilateral consent:replication peering today; user-data consent:state via
     * the existing Consent surface). Live (no gate).
     */
    object ManageConsent : NavSurface("manage-consent", "Manage Consent", CIRISIcons.lock,
        labelKey = "nav.surface.manage_consent")

    /**
     * Contacts / Identities — browsable list of known federation identities
     * (the local node's peer store). Used both as a first-class explore surface
     * and as the picker when delegating to an existing fed-ID. Live (no gate).
     */
    object Contacts : NavSurface("contacts", "Contacts", CIRISIcons.person,
        labelKey = "nav.surface.contacts")

    /**
     * Delegations — who the owner has authorized to act on their behalf (active
     * device-authorization grants), plus approve-a-new / revoke. The
     * human-consent gate for an agent acting on-behalf-of. Live (no gate).
     */
    object Delegations : NavSurface("delegations", "Delegations", CIRISIcons.keySecure,
        labelKey = "nav.surface.delegations")

    /**
     * Identity Management — manage your self fed-ID + the roster of devices
     * (occurrences) bound to it, add a new device, revoke a lost / stolen one, or
     * "log in as yourself on another device". Laptop-loss resilience (§5.6.8.8 /
     * §11.7). The app holds no keys; the node signs. Live (no gate).
     */
    object IdentityManagement : NavSurface("identity-management", "My Identity", CIRISIcons.identity,
        labelKey = "nav.surface.identity_management")

    /**
     * Accord — the HUMANITY_ACCORD constitutional surface (CIRISServer #41). The
     * entrenched accord family + its `quorum:2/3` kill-switch consensus protocol,
     * the FIPS / hardware-attested holder roster, and the pending invocations
     * (with the CC 4.2.1 per-kind visual treatment) the local holder may concur
     * on. Read view + owner-gated concur; the app holds no keys. Live (no gate).
     */
    object Accord : NavSurface("accord", "Trust Root", CIRISIcons.shield,
        labelKey = "nav.surface.accord")

    /**
     * Provision Accord Holder — the foolproof guided flow (CIRISServer #41, the
     * safe-mesh custody floor). A would-be accord holder mints their portable-2FA
     * HUMANITY_ACCORD identity from an already-FIPS-approved FIPS YubiKey + a
     * chosen ML-DSA USB path, producing the holder record + custody attestation
     * the node owner then registers. Drives the loopback-only
     * `POST /v1/accord/provision-holder`; the app holds no keys (the node does the
     * crypto). Reachable from the Accord screen + the Manage group. Live (no gate).
     */
    object ProvisionAccordHolder : NavSurface(
        id = "provision-accord-holder", label = "Provision Holder", icon = CIRISIcons.keySecure,
        labelKey = "nav.surface.provision_accord_holder",
    )

    /**
     * Accord Genesis Ceremony — the foolproof guided wizard (CIRISServer #41) that
     * stands up a NEW mesh's 2-of-3 human kill-switch: 3 humans, each a primary
     * SEAT + a cold SPARE (6 keys). Provisions + registers each key, then the 3
     * primaries cosign the family envelope and the node assembles the genesis (the
     * cold-start bake artifact). Reachable from the Accord screen ONLY when no
     * accord family exists yet. Drives the loopback + owner-gated accord endpoints;
     * the app holds no keys (the re-inserted YubiKey signs). Live (no gate).
     */
    object AccordCeremony : NavSurface(
        id = "accord-ceremony", label = "Genesis Ceremony", icon = CIRISIcons.shield,
        labelKey = "nav.surface.accord_ceremony",
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // Safety group — the holistic SAFETY surface (CIRISServer v0.4.6
    // /v1/safety/*). Safety is built in FIRST, ahead of content: a Discord /
    // Facebook / Wikipedia / YouTube superset where moderation + child-safety
    // are first-class fabric primitives, not bolt-ons. Live (no gate) — the
    // node ships the endpoints today; the app only drives the local node.
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Moderation — file a ModerationEvent (`POST /v1/safety/moderation`), see the
     * named-moderator existence status (`GET /v1/safety/named-moderator/{c}`),
     * and the delegable-duty concept. Moderation is a DUTY, not a role.
     */
    object Moderation : NavSurface("moderation", "Moderation", CIRISIcons.handler,
        labelKey = "nav.surface.moderation")

    /**
     * Child Safety — per-group content watchlist (opt-in, default OFF, NEVER
     * global; `POST/GET /v1/safety/watchlist`) + the protective posture
     * (`GET /v1/safety/status`). Honest framing is load-bearing.
     */
    object ChildSafety : NavSurface("child-safety", "Child Safety", CIRISIcons.shield,
        labelKey = "nav.surface.child_safety")

    object Wallet : NavSurface("wallet", "Wallet", CIRISIcons.keySecure,
        labelKey = "nav.surface.wallet")
    object Billing : NavSurface(
        id = "billing", label = "Billing", icon = CIRISIcons.wallet,
        labelKey = "nav.surface.billing")

    // ═══════════════════════════════════════════════════════════════════════════
    // Commons group — 5 UX-facing CEG 0.6 cohort scopes (per CEG §02 grammar:137).
    // The 7 → 5 fold: self / family / community / affiliations are 1:1; species +
    // planet + federation merge into Global Commons. See `CohortScope.kt` for the
    // mapping rationale. Phase A (2026-05-31): all 5 render LayerHubScreen with
    // section stubs (Identities · Trust · Policies) pinned to EDGE_PEERRESOLVER.
    // Phase B folds the existing Network federation hub into LayerGlobalCommons.
    // ═══════════════════════════════════════════════════════════════════════════

    /** Self — the agent itself. Implicit in the user's mental model. */
    object LayerAgent : NavSurface(
        id = "layer-agent", label = "Agent (Self)", icon = CIRISIcons.person,
        labelKey = "commons.layer.agent.title",
    )

    // ── Surfaces folded onto scale layers below (was the standalone Federation
    // group, deleted 2.9.6). Defined before the Layer* parents that nest them.
    object EnvironmentGraph : NavSurface(
        // Ungated 2.9.6 — routes to the live EnvironmentInfo screen
        // (/v1/memory?scope=environment). Reachable under Local Community.
        id = "environment-graph", label = "Environment Graph", icon = CIRISIcons.snapshot,
        labelKey = "commons.federation.environment_graph.title",
    )
    object Delegation : NavSurface(
        id = "delegation", label = "Delegation", icon = CIRISIcons.send,
        labelKey = "commons.federation.delegation.title",
    )
    object Constitutional : NavSurface(
        id = "constitutional", label = "Constitutional", icon = CIRISIcons.instructions,
        labelKey = "commons.federation.constitutional.title",
    )

    // ── The edge v18 trio — gated placeholders (CIRISServer#451). Each is a
    // real registry consumer so the reservation cannot rot as a dead entry.

    /**
     * **Commons** — the reverse-quorum plane a community polices itself with
     * (CIRISServer#367, `src/commons_surface.rs`): raise an objection, see the
     * fold's standing on one action, ballot on what the stewards left open, and
     * dismiss at the cohort's own m-of-n.
     *
     * It sits in the COMMONS group and NOT under Settings/Config, because that
     * is where its authority comes from. The `/v1/admin` routes and `/v1/mesh-config`
     * are authority acting ON a node; this is the commons acting on itself, and
     * one member is enough to raise a brake. The cohorts whose rosters supply
     * the quorum are the layers beside it. Live (no gate).
     */
    object Commons : NavSurface("commons", "Commons", CIRISIcons.handler,
        labelKey = "nav.surface.commons")

    /** Other CIRIS occurrences sharing the operator's identity. */
    object LayerFamily : NavSurface(
        id = "layer-family", label = "Family", icon = CIRISIcons.home,
        labelKey = "commons.layer.family.title",
    )

    /** One home channel / Discord guild / household — locally-trusted peers. */
    object LayerLocalCommunity : NavSurface(
        id = "layer-local-community", label = "Local Community", icon = CIRISIcons.location,
        labelKey = "commons.layer.local_community.title",
    )

    /** Cross-community affinity groups the agent has joined (CEG affiliations). */
    object LayerGlobalCommunities : NavSurface(
        id = "layer-global-communities", label = "Global Communities", icon = CIRISIcons.shield,
        labelKey = "commons.layer.global_communities.title",
    )

    /** The federation as the universal layer (folds species + planet + federation). */
    object LayerGlobalCommons : NavSurface(
        id = "layer-global-commons", label = "Global Commons", icon = CIRISIcons.globe,
        labelKey = "commons.layer.global_commons.title",
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // Client group — multi-agent + interface
    // ═══════════════════════════════════════════════════════════════════════════
    object ClientInterface : NavSurface("client-interface", "Interface", CIRISIcons.home,
        labelKey = "nav.surface.client_interface")

    /** Help — under My things, always reachable. */
    object Help : NavSurface("help", "Help", CIRISIcons.info, labelKey = "nav.surface.help")
}

/**
 * Surfaces NOT placed in any circle or instrument — flow-only screens reached
 * by direct app routing (the pre-login flow, ceremonies, the top-bar
 * utilities). Listed here so the nav module has a single authoritative
 * inventory for the no-orphans test in `CirclesNavTest`.
 *
 * IDs only (no NavSurface instances) — these aren't navigable from the shell.
 */
val FLOW_ONLY_SURFACES = listOf(
    "startup",
    "login",
    "setup",
    "server-connection",
    "accord-ceremony",
)
