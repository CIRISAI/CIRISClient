package ai.ciris.mobile.shared.calls

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** A browser tab is not a node: the call runs on the node it talks to, whose network this is not. */
actual fun networkMetered(): Flow<Boolean?> = flowOf(null)
