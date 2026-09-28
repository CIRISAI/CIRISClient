package ai.ciris.mobile.shared.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CSD-021 §6.4: the client called `disable` and never `enable`, and told the
 * person to reset their account to turn hosted services back on. The agent
 * serves both (CIRISAgent `routes/system/llm_routes.py:1029, 1081`).
 */
class CirisServicesToggleTest {

    @Test
    fun enablingAsksTheEnableRoute() {
        assertEquals("/v1/system/llm/ciris-services/enable", cirisServicesTogglePath(enable = true))
    }

    @Test
    fun disablingStillAsksTheDisableRoute() {
        assertEquals("/v1/system/llm/ciris-services/disable", cirisServicesTogglePath(enable = false))
    }
}
