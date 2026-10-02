package ai.ciris.mobile.shared.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class NodeDeviceClassTest {
    @Test
    fun a_node_the_desktop_app_starts_is_a_laptop_by_default() {
        assertEquals("laptop", NodeDeviceClass.forDesktopSpawn(null))
        assertEquals("laptop", NodeDeviceClass.forDesktopSpawn(""))
    }

    @Test
    fun a_valid_class_the_user_set_is_kept() {
        assertEquals("server", NodeDeviceClass.forDesktopSpawn("server"))
        assertEquals("embedded", NodeDeviceClass.forDesktopSpawn(" Embedded "))
    }

    @Test
    fun a_value_the_server_would_reject_becomes_laptop_not_server() {
        // The server falls back to `server` for an invalid value, which is the
        // class that stops receiving a person's self and family content.
        assertEquals("laptop", NodeDeviceClass.forDesktopSpawn("desktop"))
    }
}
