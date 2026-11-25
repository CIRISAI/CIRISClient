package ai.ciris.mobile.shared.calls

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** A desktop OS gives no portable "metered" signal. Never asked: only a phone carrying its own call asks. */
actual fun networkMetered(): Flow<Boolean?> = flowOf(null)
