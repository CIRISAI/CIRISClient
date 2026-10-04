package ai.ciris.mobile.shared.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class NodeDeviceClassTest {
    @Test
    fun a_node_the_desktop_app_starts_is_a_laptop_when_nothing_is_set() {
        assertEquals("laptop", NodeDeviceClass.forDesktopSpawn(null))
        assertEquals("laptop", NodeDeviceClass.forDesktopSpawn(""))
        assertEquals("laptop", NodeDeviceClass.forDesktopSpawn("  "))
    }

    @Test
    fun a_value_the_user_set_is_passed_through_for_the_server_to_judge() {
        // No client-side allow-list: the server owns the set of valid classes,
        // so a class it adds later is honoured without a client release.
        assertEquals("server", NodeDeviceClass.forDesktopSpawn("server"))
        assertEquals("kiosk", NodeDeviceClass.forDesktopSpawn("kiosk"))
    }

    @Test
    fun a_set_value_is_normalized_before_it_is_passed_through() {
        // The server reads canonical spellings only; " Embedded " would fall
        // back to `server` and lose the person's self and family content.
        assertEquals("embedded", NodeDeviceClass.forDesktopSpawn(" Embedded "))
        assertEquals("laptop", NodeDeviceClass.forDesktopSpawn("LAPTOP"))
    }
}
