package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.models.ImportedSkillData
import ai.ciris.mobile.shared.models.SkillImportResult
import ai.ciris.mobile.shared.models.SkillPreviewData
import ai.ciris.mobile.shared.models.importAllowed
import ai.ciris.mobile.shared.platform.PlatformLogger
import ai.ciris.mobile.shared.ui.screens.ReadFailure
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the agent already carries (`GET /v1/system/adapters/imported-skills`).
 * Three arms so a failed read never renders as "no skills" (CSD/3 §2.2).
 */
sealed interface ImportedSkillsState {
    data object Loading : ImportedSkillsState
    data class Loaded(val skills: List<ImportedSkillData>) : ImportedSkillsState
    data class Failed(val failure: ReadFailure) : ImportedSkillsState
}

/**
 * ViewModel for OpenClaw skill import feature — the paste door and the held
 * list of CSD-015 (Skills). Hosted by Screen.SkillStudio since the former
 * Screen.SkillImport was folded into CSD-015.
 *
 * Manages three workflows:
 * 1. Browse/manage previously imported skills
 * 2. Preview a SKILL.md before importing
 * 3. Import and auto-load a skill as an adapter
 */
class SkillImportViewModel(
    private val apiClient: CIRISApiClient
) : ViewModel() {

    // ===== Imported Skills List =====
    private val _importedSkills = MutableStateFlow<ImportedSkillsState>(ImportedSkillsState.Loading)
    val importedSkills: StateFlow<ImportedSkillsState> = _importedSkills.asStateFlow()

    /** The skill whose removal is awaiting the person's ConfirmSheet; null when none. */
    private val _pendingRemoval = MutableStateFlow<ImportedSkillData?>(null)
    val pendingRemoval: StateFlow<ImportedSkillData?> = _pendingRemoval.asStateFlow()

    // ===== Import Dialog State =====
    private val _showImportDialog = MutableStateFlow(false)
    val showImportDialog: StateFlow<Boolean> = _showImportDialog.asStateFlow()

    private val _skillMdContent = MutableStateFlow("")
    val skillMdContent: StateFlow<String> = _skillMdContent.asStateFlow()

    private val _sourceUrl = MutableStateFlow("")
    val sourceUrl: StateFlow<String> = _sourceUrl.asStateFlow()

    private val _preview = MutableStateFlow<SkillPreviewData?>(null)
    val preview: StateFlow<SkillPreviewData?> = _preview.asStateFlow()

    private val _importResult = MutableStateFlow<SkillImportResult?>(null)
    val importResult: StateFlow<SkillImportResult?> = _importResult.asStateFlow()

    // ===== Loading / Error =====
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    // ===== Import Dialog Phase =====
    enum class ImportPhase { PASTE, PREVIEW, RESULT }

    private val _importPhase = MutableStateFlow(ImportPhase.PASTE)
    val importPhase: StateFlow<ImportPhase> = _importPhase.asStateFlow()

    // ===== Actions =====

    fun fetchImportedSkills() {
        viewModelScope.launch {
            _importedSkills.value = ImportedSkillsState.Loading
            try {
                val skills = apiClient.listImportedSkills()
                _importedSkills.value = ImportedSkillsState.Loaded(skills)
                PlatformLogger.i("SkillImportVM", "Fetched ${skills.size} imported skills")
            } catch (e: Exception) {
                PlatformLogger.e("SkillImportVM", "Failed to fetch imported skills: ${e.message}")
                _importedSkills.value = ImportedSkillsState.Failed(ReadFailure.of(e))
            }
        }
    }

    fun openImportDialog() {
        _showImportDialog.value = true
        _importPhase.value = ImportPhase.PASTE
        _skillMdContent.value = ""
        _sourceUrl.value = ""
        _preview.value = null
        _importResult.value = null
        _error.value = null
    }

    fun closeImportDialog() {
        _showImportDialog.value = false
        _importPhase.value = ImportPhase.PASTE
        _preview.value = null
        _importResult.value = null
        _error.value = null
    }

    fun updateSkillMdContent(content: String) {
        _skillMdContent.value = content
    }

    fun updateSourceUrl(url: String) {
        _sourceUrl.value = url
    }

    fun previewSkill() {
        val content = _skillMdContent.value
        if (content.isBlank()) {
            _error.value = "Please paste SKILL.md content"
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val url = _sourceUrl.value.ifBlank { null }
                val result = apiClient.previewSkillImport(content, url)
                _preview.value = result
                _importPhase.value = ImportPhase.PREVIEW
                PlatformLogger.i("SkillImportVM", "Preview: ${result.name} v${result.version}")
            } catch (e: Exception) {
                PlatformLogger.e("SkillImportVM", "Preview failed: ${e.message}")
                _error.value = "Preview failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun importSkill() {
        val content = _skillMdContent.value
        if (content.isBlank()) return
        // Only a scan that ran and cleared the skill may import it (CSD-015).
        // The agent refuses the same at POST /import-skill; this keeps the
        // button from promising what the agent will refuse.
        if (_preview.value?.importAllowed() != true) return

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val url = _sourceUrl.value.ifBlank { null }
                val result = apiClient.importSkill(content, url, autoLoad = true)
                _importResult.value = result
                _importPhase.value = ImportPhase.RESULT

                if (result.success) {
                    _statusMessage.value = result.message
                    // Refresh the list
                    fetchImportedSkills()
                } else {
                    _error.value = "Import failed: ${result.message}"
                }
                PlatformLogger.i("SkillImportVM", "Import result: ${result.message}")
            } catch (e: Exception) {
                PlatformLogger.e("SkillImportVM", "Import failed: ${e.message}")
                _error.value = "Import failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** Ask to remove [skill]: opens the ConfirmSheet; nothing is sent yet. */
    fun requestRemoval(skill: ImportedSkillData) {
        _pendingRemoval.value = skill
    }

    fun cancelRemoval() {
        _pendingRemoval.value = null
    }

    /** The person confirmed: `DELETE /v1/system/adapters/imported-skills/{module}`. */
    fun confirmRemoval() {
        val skill = _pendingRemoval.value ?: return
        _pendingRemoval.value = null
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                apiClient.deleteImportedSkill(skill.moduleName)
                _statusMessage.value = skill.moduleName
                fetchImportedSkills()
            } catch (e: Exception) {
                _error.value = e.message ?: e::class.simpleName
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }
}
