package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.EnrichmentCacheStatsData
import ai.ciris.mobile.shared.api.EnvironmentGraphNodeData
import ai.ciris.mobile.shared.api.LocationInfoData
import ai.ciris.mobile.shared.platform.PlatformLogger
import ai.ciris.mobile.shared.ui.screens.ReadFailure
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State for the Environment Info screen.
 */
data class EnvironmentInfoScreenState(
    val location: LocationInfoData? = null,
    val items: List<EnvironmentGraphNodeData> = emptyList(),
    val contextEnrichment: Map<String, Any> = emptyMap(),
    val cacheStats: EnrichmentCacheStatsData? = null,
    val selectedCategory: String? = null, // null = all categories
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isCreating: Boolean = false,
    val error: String? = null,
    val showAddDialog: Boolean = false,
    /**
     * Is an agent attached? Adding and deleting items (`POST /v1/memory/store`,
     * `DELETE /v1/memory/{id}`) and the enrichment read are the AGENT's; the
     * node's memory API is read-only (`memory_api.rs:1265-1271`). Without an
     * agent those controls are not offered and that read is not made (CSD-002).
     */
    val agentAttached: Boolean = false,
    /** The items read failed — said, never drawn as "no items" (CSD-002, CSD/3 §2.2). */
    val itemsFailure: ReadFailure? = null,
) {
    val filteredItems: List<EnvironmentGraphNodeData>
        get() = if (selectedCategory == null) items
                else items.filter { it.category == selectedCategory }

    val categoryCounts: Map<String, Int>
        get() = items.groupBy { it.category }.mapValues { it.value.size }
}

/**
 * ViewModel for the Environment Info screen.
 *
 * Shows:
 * - User location from setup (lat/long, timezone, city)
 * - Environment items (shopping list style with categories)
 * - Context enrichment results from adapters (weather, HA entities, etc.)
 */
class EnvironmentInfoViewModel(
    private val apiClient: CIRISApiClient
) : ViewModel() {

    companion object {
        private const val TAG = "EnvironmentInfoViewModel"
    }

    private fun log(level: String, method: String, message: String) {
        val fullMessage = "[$method] $message"
        when (level) {
            "DEBUG" -> PlatformLogger.d(TAG, fullMessage)
            "INFO" -> PlatformLogger.i(TAG, fullMessage)
            "WARN" -> PlatformLogger.w(TAG, fullMessage)
            "ERROR" -> PlatformLogger.e(TAG, fullMessage)
            else -> PlatformLogger.i(TAG, fullMessage)
        }
    }

    private fun logDebug(method: String, message: String) = log("DEBUG", method, message)
    private fun logInfo(method: String, message: String) = log("INFO", method, message)
    private fun logError(method: String, message: String) = log("ERROR", method, message)

    // State
    private val _state = MutableStateFlow(EnvironmentInfoScreenState())
    val state: StateFlow<EnvironmentInfoScreenState> = _state.asStateFlow()

    private var dataLoadStarted = false

    init {
        logInfo("init", "EnvironmentInfoViewModel initialized")
    }

    /**
     * Start loading environment info.
     */
    fun startPolling() {
        val method = "startPolling"
        if (dataLoadStarted) {
            logDebug(method, "Data load already started, skipping")
            return
        }
        dataLoadStarted = true
        loadAll()
    }

    /**
     * Refresh all data.
     */
    fun refresh() {
        val method = "refresh"
        logInfo(method, "Refreshing environment info")
        _state.update { it.copy(isRefreshing = true, error = null) }
        loadAll()
    }

    /**
     * Set selected category filter.
     */
    fun setCategory(category: String?) {
        _state.update { it.copy(selectedCategory = category) }
    }

    /** Set from the probed `ClientMode`; a change re-reads. */
    fun setAgentAttached(attached: Boolean) {
        if (_state.value.agentAttached == attached) return
        _state.update { it.copy(agentAttached = attached, showAddDialog = it.showAddDialog && attached) }
        if (dataLoadStarted) loadAll()
    }

    /**
     * Show/hide add item dialog.
     */
    fun showAddDialog(show: Boolean) {
        _state.update { it.copy(showAddDialog = show) }
    }

    /**
     * Create a new environment item.
     */
    fun createItem(
        name: String,
        category: String,
        quantity: Int,
        condition: String,
        notes: String?
    ) {
        val method = "createItem"
        logInfo(method, "Creating item: $name")

        viewModelScope.launch {
            _state.update { it.copy(isCreating = true) }
            try {
                val newItem = apiClient.createEnvironmentItem(
                    name = name,
                    category = category,
                    quantity = quantity,
                    condition = condition,
                    notes = notes
                )
                _state.update {
                    it.copy(
                        items = it.items + newItem,
                        isCreating = false,
                        showAddDialog = false
                    )
                }
                logInfo(method, "Item created: ${newItem.id}")
            } catch (e: Exception) {
                logError(method, "Failed to create item: ${e.message}")
                _state.update {
                    it.copy(
                        isCreating = false,
                        error = "Failed to create item: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Delete an environment item.
     */
    fun deleteItem(nodeId: String) {
        val method = "deleteItem"
        logInfo(method, "Deleting item: $nodeId")

        viewModelScope.launch {
            try {
                val success = apiClient.deleteEnvironmentItem(nodeId)
                if (success) {
                    _state.update {
                        it.copy(items = it.items.filter { item -> item.id != nodeId })
                    }
                    logInfo(method, "Item deleted: $nodeId")
                } else {
                    _state.update { it.copy(error = "Failed to delete item") }
                }
            } catch (e: Exception) {
                logError(method, "Failed to delete item: ${e.message}")
                _state.update { it.copy(error = "Failed to delete item: ${e.message}") }
            }
        }
    }

    private fun loadAll() {
        val method = "loadAll"
        logInfo(method, "Loading all environment data")

        viewModelScope.launch {
            try {
                if (!_state.value.isRefreshing) {
                    _state.update { it.copy(isLoading = true, error = null) }
                }

                // Load items from memory API. A failure is KEPT: it used to
                // become an empty list, drawn as "no items" (CSD-002).
                var itemsFailure: ReadFailure? = null
                val items = try {
                    apiClient.queryEnvironmentItems()
                } catch (e: Exception) {
                    logError(method, "Failed to load items: ${e.message}")
                    itemsFailure = ReadFailure.of(e)
                    emptyList()
                }

                // Load context enrichment — the agent's adapters; a bare node
                // has none and is not asked (CSD-002).
                val (enrichment, stats) = if (!_state.value.agentAttached) {
                    Pair(emptyMap<String, Any>(), null)
                } else try {
                    val response = apiClient.getContextEnrichment()
                    Pair(response.entries, response.stats)
                } catch (e: Exception) {
                    logError(method, "Failed to load context enrichment: ${e.message}")
                    Pair(emptyMap<String, Any>(), null)
                }

                logInfo(method, "Loaded ${items.size} items, ${enrichment.size} enrichment entries")

                _state.update {
                    it.copy(
                        items = items,
                        itemsFailure = itemsFailure,
                        contextEnrichment = enrichment,
                        cacheStats = stats,
                        isLoading = false,
                        isRefreshing = false,
                        error = null
                    )
                }
            } catch (e: Exception) {
                logError(method, "Error loading data: ${e.message}")
                _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = e.message ?: "Unknown error"
                    )
                }
            }
        }
    }
}
