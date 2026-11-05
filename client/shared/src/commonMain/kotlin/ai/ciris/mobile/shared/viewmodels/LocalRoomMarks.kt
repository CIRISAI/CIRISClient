package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.platform.SecureStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The rooms this device founded as local.
 *
 * A node ignores `cohort_subkind` until CIRISServer#852, so a room founded from
 * Local community is an ordinary `community` room on the wire. This is what
 * keeps it on the Local list, and only here: someone else in the room sees it
 * under Communities. Once a node sends `cohort_subkind`, that decides, and these
 * marks only matter for rooms founded before it.
 */
class LocalRoomMarks(
    private val read: suspend () -> String? = { null },
    private val write: suspend (String) -> Unit = {},
) {
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    suspend fun load() {
        _ids.value += decode(read())
    }

    suspend fun add(communityId: String) {
        _ids.value += communityId
        write(_ids.value.joinToString(SEPARATOR))
    }

    companion object {
        private const val STORAGE_KEY = "local_room_ids"
        private const val SEPARATOR = "\n"

        fun stored(storage: SecureStorage) = LocalRoomMarks(
            read = { storage.get(STORAGE_KEY).getOrNull() },
            write = { storage.save(STORAGE_KEY, it) },
        )

        private fun decode(raw: String?): Set<String> =
            raw?.split(SEPARATOR)?.filter { it.isNotBlank() }?.toSet().orEmpty()
    }
}
