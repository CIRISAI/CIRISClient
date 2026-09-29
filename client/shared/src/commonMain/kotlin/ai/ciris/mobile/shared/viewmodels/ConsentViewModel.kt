package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.ui.screens.ReadFailure
import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.models.AgentRead
import ai.ciris.mobile.shared.models.PartnershipHistoryDto
import ai.ciris.mobile.shared.models.PartnershipOptionsDto
import ai.ciris.mobile.shared.models.PartnershipQueue
import ai.ciris.mobile.shared.models.partnershipQueueOf
import ai.ciris.mobile.shared.platform.PlatformLogger
import ai.ciris.mobile.shared.ui.screens.ConsentAuditEntryData
import ai.ciris.mobile.shared.ui.screens.ConsentImpactData
import ai.ciris.mobile.shared.ui.screens.ConsentScreenData
import ai.ciris.mobile.shared.ui.screens.ConsentStreamInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ViewModel for ConsentScreen
 * Handles consent management operations
 *
 * Features:
 * - Load consent status and available streams
 * - Change consent stream
 * - Request partnership
 * - Load impact data and audit trail
 * - Poll for partnership status when pending
 */
class ConsentViewModel(
    private val apiClient: CIRISApiClient,
    /** Where the card starts. Empty in the app; a test seeds a previous owner's record to forget. */
    initialData: ConsentScreenData = ConsentScreenData(),
    /** The record read (status, streams, partnership, impact, audit). The API's by default; a test holds it open across a reset. */
    readRecord: (suspend () -> ConsentScreenData)? = null,
    /** The partnership-queue read (options, pending, metrics). */
    readQueue: (suspend () -> PartnershipQueueRead)? = null,
    /** One person's partnership history. */
    readHistory: (suspend (String) -> AgentRead<PartnershipHistoryDto>)? = null,
) : ViewModel() {

    /** What [loadPartnershipQueue] publishes: the options read and the queue. */
    data class PartnershipQueueRead(
        val options: AgentRead<PartnershipOptionsDto>,
        val queue: PartnershipQueue,
    )

    private val recordReader: suspend () -> ConsentScreenData = readRecord ?: { readRecordFromApi() }
    private val queueReader: suspend () -> PartnershipQueueRead = readQueue ?: { readQueueFromApi() }
    private val historyReader: suspend (String) -> AgentRead<PartnershipHistoryDto> =
        readHistory ?: { userId -> apiClient.partnershipHistory(userId) }

    companion object {
        private const val TAG = "ConsentViewModel"
        private const val PARTNERSHIP_POLL_INTERVAL_MS = 5000L
    }

    /**
     * CIRISApp's token effect, the same one that resets Manage Consent
     * (CSD-053, PR #116): [authenticated] is `consentSessionAuthenticated` of
     * the token and the Home Assistant add-on mode. A session that ends
     * forgets the record; nothing is loaded here on a session that begins —
     * the screen loads when it is shown.
     */
    fun sessionChanged(authenticated: Boolean) {
        if (!authenticated) resetSession()
    }

    /**
     * The session ended — every way out. This model is CIRISApp-scoped and
     * outlives the owner, and the screen's spinner shows only while it holds
     * NO record, so without this the next signer-in read the previous owner's
     * stream, expiry, audit trail and partnership queue for the whole reload
     * (CSD-054 §2; the Manage Consent half of the same defect was PR #116).
     */
    fun resetSession() {
        // Every read in flight belongs to the session that just ended: cancel
        // them, and advance the epoch so one already past its last suspension
        // point discards its result instead of publishing it (Codex, PR #126).
        sessionEpoch += 1
        sessionJobs.toList().forEach { it.cancel() }
        sessionJobs.clear()
        stopPartnershipPolling()
        dataLoadStarted = false
        _isLoading.value = false
        _consentData.value = ConsentScreenData()
        _error.value = null
        _successMessage.value = null
        _partnershipOptions.value = null
        _partnershipQueue.value = PartnershipQueue.Loading
        _partnershipHistory.value = emptyMap()
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
    private fun logWarn(method: String, message: String) = log("WARN", method, message)
    private fun logError(method: String, message: String) = log("ERROR", method, message)

    // Consent data state
    private val _consentData = MutableStateFlow(initialData)
    val consentData: StateFlow<ConsentScreenData> = _consentData.asStateFlow()

    // Loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Error state
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Success message
    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

    // Partnership polling job
    private var partnershipPollJob: Job? = null
    private var dataLoadStarted = false

    /**
     * Session epoch: advanced by [resetSession]. Every coroutine that publishes
     * into this model captures it at LAUNCH and re-checks before publishing —
     * the reset empties the flows exactly once, and without the gate a read
     * still in flight at logout repopulated them with the previous owner's
     * stream, audit trail and partnership queue (the same gate as
     * ContactsViewModel / ConsentObjectsViewModel). Confined to the main
     * dispatcher by `viewModelScope`, so a plain Long suffices.
     */
    private var sessionEpoch: Long = 0L

    /** The reads in flight for this session; [resetSession] cancels them. */
    private val sessionJobs = mutableSetOf<Job>()

    /** Launch a read owned by the current session: tracked, and given its epoch. */
    private fun launchInSession(block: suspend (epoch: Long) -> Unit): Job {
        val epoch = sessionEpoch
        val job = viewModelScope.launch { block(epoch) }
        if (job.isActive) {
            sessionJobs += job
            job.invokeOnCompletion { sessionJobs -= job }
        }
        return job
    }

    private fun current(epoch: Long) = epoch == sessionEpoch

    init {
        logInfo("init", "ConsentViewModel initialized (data load deferred until startPolling() called)")
        // NOTE: Don't auto-load here - wait for startPolling() to be called
        // when the screen becomes visible and has a valid auth token
    }

    /**
     * Start consent data loading.
     * Must be called explicitly when the screen becomes visible.
     */
    fun startPolling() {
        val method = "startPolling"
        if (dataLoadStarted) {
            logDebug(method, "Data load already started, skipping")
            return
        }
        dataLoadStarted = true
        logInfo(method, "Starting consent data loading")
        loadConsentData()
        loadPartnershipQueue()
    }

    /**
     * Stop polling (for lifecycle management)
     */
    fun stopPolling() {
        val method = "stopPolling"
        logInfo(method, "Stopping consent polling")
        stopPartnershipPolling()
        dataLoadStarted = false // Allow restart
    }

    /**
     * Load all consent data from API
     */
    fun loadConsentData() {
        val method = "loadConsentData"
        logInfo(method, "Loading consent data")

        launchInSession { epoch ->
            _isLoading.value = true
            _error.value = null

            try {
                val record = recordReader()
                if (!current(epoch)) return@launchInSession
                _consentData.value = record
                val isPending = record.partnershipPending

                logInfo(method, "Consent data loaded: hasConsent=${record.hasConsent}, " +
                        "stream=${record.currentStream}, partnershipPending=$isPending")

                // Start polling if partnership is pending
                if (isPending) {
                    startPartnershipPolling()
                } else {
                    stopPartnershipPolling()
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!current(epoch)) return@launchInSession
                logError(method, "Failed to load consent data: ${e::class.simpleName}: ${e.message}")
                _error.value = "Failed to load consent data: ${e.message}"
                _consentData.value = ConsentScreenData(readFailure = ReadFailure.of(e))
            } finally {
                // A superseded load must not clear the next session's spinner.
                if (current(epoch)) _isLoading.value = false
            }
        }
    }

    /** The record, read from the agent. Throws on a failed status or streams read. */
    private suspend fun readRecordFromApi(): ConsentScreenData {
        val method = "readRecordFromApi"
        // Load consent status. No 404 swallow: the agent answers "no
        // record" with 200 + has_consent=false (routes/consent.py), so a
        // 404 means THIS HOST HAS NO CONSENT ROUTE (a node), not "a new
        // user with no record". Treating it as the latter told a node
        // owner they had never consented, about a question nobody could
        // answer (CSD-054). It now fails the load and says which.
        val statusResponse = apiClient.getConsentStatus()

        // Load available streams
        val streamsResponse = apiClient.getConsentStreams()

        // Load partnership status
        val partnershipResponse = try {
            apiClient.getPartnershipStatus()
        } catch (e: Exception) {
            logWarn(method, "Failed to load partnership status: ${e.message}")
            null
        }

        // Load impact data if applicable
        val impactData = if (statusResponse?.stream in listOf("partnered", "anonymous")) {
            try {
                apiClient.getConsentImpact()
            } catch (e: Exception) {
                logWarn(method, "Failed to load impact data: ${e.message}")
                null
            }
        } else null

        // Load audit trail
        val auditEntries = try {
            apiClient.getConsentAudit(10)
        } catch (e: Exception) {
            logWarn(method, "Failed to load audit trail: ${e.message}")
            emptyList()
        }

        // Build stream info list with benefits
        val availableStreams = streamsResponse.streams.map { (id, metadata) ->
            ConsentStreamInfo(
                id = id,
                name = metadata.name,
                description = metadata.description,
                durationDays = metadata.durationDays,
                autoForget = metadata.autoForget,
                learningEnabled = metadata.learningEnabled,
                identityRemoved = metadata.identityRemoved,
                requiresApproval = metadata.requiresCategories,
                benefits = getStreamBenefits(id)
            )
        }

        val isPending = partnershipResponse?.status == "pending"

        return ConsentScreenData(
            hasConsent = statusResponse != null,
            currentStream = statusResponse?.stream,
            expiresAt = statusResponse?.expiresAt,
            partnershipPending = isPending,
            availableStreams = availableStreams,
            impactData = impactData?.let {
                ConsentImpactData(
                    totalInteractions = it.totalInteractions,
                    patternsContributed = it.patternsContributed,
                    usersHelped = it.usersHelped,
                    impactScore = it.impactScore
                )
            },
            auditEntries = auditEntries.map {
                ConsentAuditEntryData(
                    entryId = it.entryId,
                    timestamp = it.timestamp,
                    previousStream = it.previousStream,
                    newStream = it.newStream,
                    initiatedBy = it.initiatedBy,
                    reason = it.reason
                )
            }
        )
    }

    private suspend fun readQueueFromApi(): PartnershipQueueRead {
        val options = apiClient.partnershipOptions()
        val pending = apiClient.partnershipPending()
        val metrics = if (pending is AgentRead.Ok) apiClient.partnershipMetrics() else null
        return PartnershipQueueRead(options, partnershipQueueOf(pending, metrics))
    }

    /**
     * Change consent stream
     */
    fun changeStream(streamId: String) {
        val method = "changeStream"
        logInfo(method, "Changing consent stream to: $streamId")

        launchInSession { epoch ->
            _isLoading.value = true
            _error.value = null

            try {
                apiClient.grantConsent(
                    stream = streamId,
                    categories = emptyList(),
                    reason = "User switched to $streamId consent via mobile app"
                )

                logInfo(method, "Stream changed successfully to $streamId")
                if (!current(epoch)) return@launchInSession
                _successMessage.value = "Consent stream changed to ${streamId.uppercase()}"
                loadConsentData() // Reload to show updated status
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(method, "Failed to change stream: ${e::class.simpleName}: ${e.message}")
                if (current(epoch)) _error.value = "Failed to change consent stream: ${e.message}"
            } finally {
                if (current(epoch)) _isLoading.value = false
            }
        }
    }

    /**
     * Request partnership
     */
    fun requestPartnership() {
        val method = "requestPartnership"
        logInfo(method, "Requesting partnership")

        launchInSession { epoch ->
            _isLoading.value = true
            _error.value = null

            try {
                apiClient.requestPartnership(
                    reason = "Partnership requested via mobile app"
                )

                logInfo(method, "Partnership request submitted")
                if (!current(epoch)) return@launchInSession
                _successMessage.value = "Partnership request submitted. The agent will review your request."

                // Update UI to show pending state
                _consentData.value = _consentData.value.copy(partnershipPending = true)

                // Start polling for status
                startPartnershipPolling()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(method, "Failed to request partnership: ${e::class.simpleName}: ${e.message}")
                if (current(epoch)) _error.value = "Failed to submit partnership request: ${e.message}"
            } finally {
                if (current(epoch)) _isLoading.value = false
            }
        }
    }

    /**
     * Start polling for partnership status
     */
    private fun startPartnershipPolling() {
        val method = "startPartnershipPolling"
        if (partnershipPollJob?.isActive == true) {
            logDebug(method, "Partnership polling already active")
            return
        }

        logInfo(method, "Starting partnership status polling")
        val epoch = sessionEpoch
        partnershipPollJob = viewModelScope.launch {
            while (isActive) {
                delay(PARTNERSHIP_POLL_INTERVAL_MS)

                try {
                    val status = apiClient.getPartnershipStatus()
                    logDebug(method, "Partnership status: ${status.status}")
                    if (!current(epoch)) break

                    if (status.status != "pending") {
                        // Status changed
                        when (status.status) {
                            "accepted" -> {
                                _successMessage.value = "Partnership approved! You now have PARTNERED consent."
                            }
                            "rejected" -> {
                                _error.value = "Partnership request was declined by the agent."
                            }
                        }
                        loadConsentData()
                        break
                    }
                } catch (e: Exception) {
                    logError(method, "Error polling partnership status: ${e.message}")
                    break
                }
            }
        }
    }

    /**
     * Stop partnership polling
     */
    private fun stopPartnershipPolling() {
        partnershipPollJob?.cancel()
        partnershipPollJob = null
    }

    /**
     * Refresh consent data
     */
    fun refresh() {
        loadConsentData()
        loadPartnershipQueue()
    }

    /**
     * Clear error state
     */
    fun clearError() {
        _error.value = null
    }

    /**
     * Clear success message
     */
    fun clearSuccess() {
        _successMessage.value = null
    }

    // ── The partnership queue (CSD-054 §7) ─────────────────────────────────
    // Requests to partner with this agent that are waiting on its answer. Read
    // only: the answer is the agent's (consent:partnership_accept is the
    // producer's half, CC 3.3.1), so nothing here calls /v1/partnership/decide.

    private val _partnershipOptions = MutableStateFlow<AgentRead<PartnershipOptionsDto>?>(null)
    /** `GET /v1/partnership/options` — null until read. */
    val partnershipOptions: StateFlow<AgentRead<PartnershipOptionsDto>?> = _partnershipOptions.asStateFlow()

    private val _partnershipQueue = MutableStateFlow<PartnershipQueue>(PartnershipQueue.Loading)
    val partnershipQueue: StateFlow<PartnershipQueue> = _partnershipQueue.asStateFlow()

    private val _partnershipHistory = MutableStateFlow<Map<String, AgentRead<PartnershipHistoryDto>?>>(emptyMap())
    /** Opened histories by user id; a null value is a read in flight. */
    val partnershipHistory: StateFlow<Map<String, AgentRead<PartnershipHistoryDto>?>> = _partnershipHistory.asStateFlow()

    fun loadPartnershipQueue() {
        launchInSession { epoch ->
            _partnershipQueue.value = PartnershipQueue.Loading
            val read = queueReader()
            if (!current(epoch)) return@launchInSession
            _partnershipOptions.value = read.options
            _partnershipQueue.value = read.queue
            logInfo("loadPartnershipQueue", "queue=${_partnershipQueue.value::class.simpleName}")
        }
    }

    /** Open or close one person's history (`GET /v1/partnership/history/{user_id}`). */
    fun togglePartnershipHistory(userId: String) {
        if (userId in _partnershipHistory.value) {
            _partnershipHistory.value = _partnershipHistory.value - userId
            return
        }
        _partnershipHistory.value = _partnershipHistory.value + (userId to null)
        launchInSession { epoch ->
            val read = historyReader(userId)
            if (current(epoch) && userId in _partnershipHistory.value) {
                _partnershipHistory.value = _partnershipHistory.value + (userId to read)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPartnershipPolling()
    }

    /**
     * Get benefits list for a stream
     */
    private fun getStreamBenefits(streamId: String): List<String> {
        return when (streamId.lowercase()) {
            "temporary" -> listOf(
                "No tracking",
                "Auto-forget in 14 days",
                "No learning"
            )
            "partnered" -> listOf(
                "Mutual growth",
                "Personalized experience",
                "Full features"
            )
            "anonymous" -> listOf(
                "Help others",
                "No identity stored",
                "Statistical contribution"
            )
            else -> emptyList()
        }
    }
}
