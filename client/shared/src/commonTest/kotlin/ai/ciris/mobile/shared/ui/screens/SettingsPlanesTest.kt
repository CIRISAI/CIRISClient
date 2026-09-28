package ai.ciris.mobile.shared.ui.screens

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CSD-022 §6.1: `CirclesNavTest` pins that a node build's NAV has no LLM or
 * Interface surface, but Settings rendered `btn_llm_settings`,
 * `btn_viz_settings` and the agent-mode section on every build, so both were
 * reachable through a door the rail had closed. This pins REACHABILITY: what
 * the Settings screen itself offers per build.
 */
class SettingsPlanesTest {

    @Test
    fun aNodeBuildIsNotOfferedTheAgentsPlane() {
        assertFalse(SettingsPlanes.showsAgentPlane(hasAgent = false))
    }

    @Test
    fun aNodeBuildIsNotOfferedAGroundSearchItsNodeCannotServe() {
        // /v1/setup/location* is served by CIRISAgent only (setup/location.py).
        assertFalse(SettingsPlanes.showsGroundSearch(hasAgent = false))
    }

    @Test
    fun anAgentBuildKeepsBoth() {
        assertTrue(SettingsPlanes.showsAgentPlane(hasAgent = true))
        assertTrue(SettingsPlanes.showsGroundSearch(hasAgent = true))
    }
}
