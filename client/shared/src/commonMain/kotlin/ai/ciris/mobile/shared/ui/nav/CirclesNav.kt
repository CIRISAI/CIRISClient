package ai.ciris.mobile.shared.ui.nav

import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.nav.CohortScope.AGENT
import ai.ciris.mobile.shared.ui.nav.CohortScope.FAMILY
import ai.ciris.mobile.shared.ui.nav.CohortScope.GLOBAL_COMMONS
import ai.ciris.mobile.shared.ui.nav.CohortScope.GLOBAL_COMMUNITIES
import ai.ciris.mobile.shared.ui.nav.CohortScope.LOCAL_COMMUNITY

/**
 * THE ONE NAV TREE — five circles, seven tabs, five instruments.
 *
 * The locked spec settles the shape: the five circles are the top level
 * ([CohortScope], in order), every circle has the same seven tabs, and the
 * two things that are not a circle — who you are, and the way to stop — live
 * in the top bar. Every existing surface is placed here exactly once: in a
 * tab for some circles ([Placement]), or under one of the five instruments
 * behind My things ([Instrument]). A surface that is neither is a flow
 * (`FLOW_ONLY_SURFACES`). `CirclesNavTest` pins that nothing is orphaned and
 * nothing is placed twice.
 *
 * The placements are the Card Atlas ("Where every existing surface goes").
 * Two axes became one: a surface no longer has a group AND a parent.
 *
 * Tags (the downstream contract — `testing/gate/nav_map.py` derives hops
 * from this file):
 *
 *     circle_<scope id, - as _>      the five circles
 *     tab_<tab id>                   the seven tabs
 *     btn_my_things                  the avatar → the instruments
 *     nav_instrument_<id>            the five instruments
 *     nav_epistemic_<surface id>     a surface, wherever it is listed
 */
enum class Tab(val id: String, val labelKey: String, val glyph: GlyphName) {
    FILES("files", "nav.tab.files", GlyphName.FILE),
    CHATS("chats", "nav.tab.chats", GlyphName.SEND),
    PEOPLE("people", "nav.tab.people", GlyphName.PERSON),
    SAFETY("safety", "nav.tab.safety", GlyphName.SAFETY),
    RULES("rules", "nav.tab.rules", GlyphName.SETTINGS),
    DECISIONS("decisions", "nav.tab.decisions", GlyphName.TASK),
    RECORD("record", "nav.tab.record", GlyphName.AUDIT);

    val tag: String get() = "tab_$id"
}

/** A surface in a tab, for the circles it applies to. */
data class Placement(
    val surface: NavSurface,
    val tab: Tab,
    val circles: Set<CohortScope>,
    /** Only offered when an agent is attached (the node build is a subset of the agent build). */
    val agentOnly: Boolean = false,
)

/** One of the five instruments behind My things. */
data class Instrument(
    val id: String,
    val labelKey: String,
    val glyph: GlyphName,
    val surfaces: List<NavSurface>,
    val agentOnly: Set<NavSurface> = emptySet(),
) {
    val tag: String get() = "nav_instrument_" + id.replace('-', '_')
    fun surfaces(hasAgent: Boolean): List<NavSurface> =
        if (hasAgent) surfaces else surfaces.filter { it !in agentOnly }
}

object CirclesNav {
    val ALL: Set<CohortScope> = CohortScope.entries.toSet()
    val FAMILY_OUT: Set<CohortScope> = setOf(FAMILY, LOCAL_COMMUNITY, GLOBAL_COMMUNITIES, GLOBAL_COMMONS)
    val NEIGHBOURS_OUT: Set<CohortScope> = setOf(LOCAL_COMMUNITY, GLOBAL_COMMUNITIES, GLOBAL_COMMONS)

    /** The circles in rail order. */
    val circles: List<CohortScope> = CohortScope.entries.toList()

    val placements: List<Placement> = listOf(
        // ── Files — FILES. The tab is named for what it holds, so nothing
        // else may sit in it: the memory graph moved to This node, the
        // environment snapshot and the commons to Decisions, the accord's
        // attestations to Safety. The one card here is the drive plane
        // itself (B3), in the circles the drive has a cohort for. ──

        Placement(NavSurface.Files, Tab.FILES, setOf(AGENT, LOCAL_COMMUNITY, GLOBAL_COMMUNITIES)),

        // ── Chats — CONVERSATIONS. The agent conversation is one today.
        // Cognitive sessions, tickets and the scheduler are how the machine
        // runs, not who you talk to; they live under This node. ──
        Placement(NavSurface.Interact, Tab.CHATS, setOf(AGENT), agentOnly = true),
        // A note to self is the chat of one, and needs no agent: every build.
        Placement(NavSurface.Notes, Tab.CHATS, setOf(AGENT)),

        // ── People — the constituent of every circle. Contacts IS the tab;
        // who may act for whom is a rule, so Delegations sits under Rules. ──
        Placement(NavSurface.Contacts, Tab.PEOPLE, ALL),
        Placement(NavSurface.Users, Tab.PEOPLE, setOf(GLOBAL_COMMUNITIES)),

        // ── Safety — what looks after the circle, and in Everyone that is the
        // accord: the 2-of-3 human kill switch, its holder roster, the flow
        // that provisions a holder, and the `accord:*` attestations only a
        // holder may emit. One place, because in an emergency a person should
        // not have to know which of three tabs we filed it under. ──
        Placement(NavSurface.Moderation, Tab.SAFETY, NEIGHBOURS_OUT),
        Placement(NavSurface.ChildSafety, Tab.SAFETY, ALL),
        Placement(NavSurface.Accord, Tab.SAFETY, setOf(GLOBAL_COMMONS)),
        Placement(NavSurface.ProvisionAccordHolder, Tab.SAFETY, setOf(GLOBAL_COMMONS)),
        Placement(NavSurface.Constitutional, Tab.SAFETY, setOf(GLOBAL_COMMONS)),

        // ── Rules — the circle's own hub first, then what you set. App and
        // model settings are NOT rules of a circle; they are this device's,
        // and they moved there. ──
        Placement(NavSurface.LayerAgent, Tab.RULES, setOf(AGENT), agentOnly = true),
        Placement(NavSurface.LayerFamily, Tab.RULES, setOf(FAMILY)),
        Placement(NavSurface.LayerLocalCommunity, Tab.RULES, setOf(LOCAL_COMMUNITY)),
        Placement(NavSurface.LayerGlobalCommunities, Tab.RULES, setOf(GLOBAL_COMMUNITIES)),
        Placement(NavSurface.LayerGlobalCommons, Tab.RULES, setOf(GLOBAL_COMMONS)),
        Placement(NavSurface.Trust, Tab.RULES, ALL),
        Placement(NavSurface.ManageConsent, Tab.RULES, ALL),
        Placement(NavSurface.Consent, Tab.RULES, ALL),
        Placement(NavSurface.Delegations, Tab.RULES, ALL),
        Placement(NavSurface.Delegation, Tab.RULES, setOf(FAMILY)),
        Placement(NavSurface.Billing, Tab.RULES, setOf(GLOBAL_COMMUNITIES)),
        Placement(NavSurface.Wallet, Tab.RULES, setOf(GLOBAL_COMMUNITIES)),

        // ── Decisions — how the circle is doing and what it is weighing:
        // standing and health, the local environment it depends on, and the
        // commons' reverse quorum. ──
        Placement(NavSurface.HealthReputation, Tab.DECISIONS, NEIGHBOURS_OUT),
        Placement(NavSurface.EnvironmentGraph, Tab.DECISIONS, setOf(LOCAL_COMMUNITY)),
        Placement(NavSurface.Commons, Tab.DECISIONS, setOf(GLOBAL_COMMONS)),

        // ── Record — one card, five homes ──
        Placement(NavSurface.Audit, Tab.RECORD, ALL),
    )

    val instruments: List<Instrument> = listOf(
        Instrument(
            "devices-keys", "nav.instrument.devices_keys", GlyphName.DEVICES,
            listOf(NavSurface.IdentityManagement, NavSurface.Account),
        ),
        Instrument(
            "everything-i-shared", "nav.instrument.everything_i_shared", GlyphName.AUDIT,
            listOf(NavSurface.Data, NavSurface.Storage),
        ),
        Instrument(
            "this-node", "nav.instrument.this_node", GlyphName.TELEMETRY,
            listOf(
                NavSurface.Nodes, NavSurface.AgentSettings, NavSurface.LLMSettings,
                NavSurface.Adapters, NavSurface.NetworkOps, NavSurface.Services,
                NavSurface.Telemetry, NavSurface.Logs, NavSurface.Transport, NavSurface.System,
                NavSurface.Runtime, NavSurface.Config, NavSurface.GraphMemory, NavSurface.Memory,
                NavSurface.Sessions, NavSurface.Tickets, NavSurface.Scheduler,
                NavSurface.Tools, NavSurface.Skills, NavSurface.ClientInterface,
            ),
            // Settings is every build's — language, ground, sign out. The rest
            // of what the brain runs on is the agent build's only.
            agentOnly = setOf(
                NavSurface.LLMSettings, NavSurface.Memory, NavSurface.Sessions, NavSurface.Tickets,
                NavSurface.Scheduler, NavSurface.Tools, NavSurface.Skills, NavSurface.ClientInterface,
            ),
        ),
        Instrument(
            "someone-i-trust", "nav.instrument.someone_i_trust", GlyphName.DEFER,
            listOf(NavSurface.WiseAuthority),
        ),
        Instrument("help", "nav.instrument.help", GlyphName.QUESTION, listOf(NavSurface.Help)),
    )

    /** The cards in a tab, for a circle, on this build. Order is the placement order. */
    fun cards(circle: CohortScope, tab: Tab, hasAgent: Boolean): List<NavSurface> =
        placements
            .filter { it.tab == tab && circle in it.circles && (hasAgent || !it.agentOnly) }
            .map { it.surface }

    fun placementOf(surface: NavSurface): Placement? = placements.firstOrNull { it.surface == surface }

    fun instrumentOf(surface: NavSurface): Instrument? = instruments.firstOrNull { surface in it.surfaces }

    /** The tab a surface is shown under, or null for an instrument surface. */
    fun tabOf(surface: NavSurface): Tab? = placementOf(surface)?.tab

    /**
     * The circle to stand in when a surface is opened with no circle context
     * (a deep route, a back target): the current circle if the surface is
     * there, else the first circle it is placed in.
     */
    fun circleFor(surface: NavSurface, current: CohortScope): CohortScope? {
        val p = placementOf(surface) ?: return null
        return if (current in p.circles) current else circles.first { it in p.circles }
    }

    fun isAgentOnly(surface: NavSurface): Boolean =
        placementOf(surface)?.agentOnly == true || instruments.any { surface in it.agentOnly }

    // ── Tags ────────────────────────────────────────────────────────────────
    fun slug(id: String): String = id.replace('-', '_')
    fun circleTag(scope: CohortScope): String = "circle_" + slug(scope.id)
    fun navTag(surface: NavSurface): String = "nav_epistemic_" + slug(surface.id)
    const val MY_THINGS_TAG = "btn_my_things"
    /** The mark in the top bar: it opens and closes the left side. */
    const val RAIL_TOGGLE_TAG = "btn_rail_toggle"
    const val STOP_TAG = "btn_stop_everything"
    const val TABS_SCROLLABLE = "shell_tabs"
    const val RAIL_SCROLLABLE = "shell_rail"

    // ── Copy keys ───────────────────────────────────────────────────────────
    fun circleNameKey(scope: CohortScope) = "nav.circle." + slug(scope.id)
    fun circleSubtitleKey(scope: CohortScope) = "nav.circle_sub." + slug(scope.id)
    fun circleRuleKey(scope: CohortScope) = "nav.circle_rule." + slug(scope.id)

    /**
     * The honest sentence for a tab with nothing in it, in this circle. A
     * circle-specific line where the reason differs (Just me has nobody to
     * decide with; Family decides by talking), the tab's line otherwise.
     */
    fun emptyKey(circle: CohortScope, tab: Tab): String = when {
        tab == Tab.DECISIONS && circle == AGENT -> "nav.empty.decisions_agent"
        tab == Tab.DECISIONS && circle == FAMILY -> "nav.empty.decisions_family"
        tab == Tab.FILES && circle == AGENT -> "nav.empty.files_agent"
        // Nothing in production can form a household family yet
        // (CIRISServer#627), so a family file cannot exist: say that, rather
        // than an "empty" that implies there could be something here.
        tab == Tab.FILES && circle == FAMILY -> "nav.empty.files_family"
        else -> "nav.empty." + tab.id
    }
}
