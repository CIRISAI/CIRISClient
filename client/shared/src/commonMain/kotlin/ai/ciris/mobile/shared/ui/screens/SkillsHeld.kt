package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.ImportedSkillData
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.ui.components.ImportedSkillCard
import ai.ciris.mobile.shared.ui.primitives.ConfirmFact
import ai.ciris.mobile.shared.ui.primitives.ConfirmSheet
import ai.ciris.mobile.shared.ui.primitives.ListState
import ai.ciris.mobile.shared.ui.primitives.StateBlock
import ai.ciris.mobile.shared.viewmodels.ImportedSkillsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * CSD-015 · Skills — what this agent already carries, and the paste door.
 *
 * The former Screen.SkillImport folded into Screen.SkillStudio: one card, the
 * Skills surface, with two doors to one act (`POST /import-skill`) — write a
 * skill here, or paste one somebody else wrote ([onPaste] opens the paste
 * door). This section is what the paste door used to hide: the list served by
 * `GET /v1/system/adapters/imported-skills`, each row removable behind a
 * ConfirmSheet (`DELETE …/imported-skills/{module}`).
 *
 * Tags: `card_skills_held` (the section), `skills_held_loading`,
 * `skills_held_empty`, `skills_held_error` / `skills_held_not_on_this_node`
 * (never alike), `item_imported_skill_{module}`, `btn_delete_skill_{module}`,
 * `btn_skill_paste`.
 */
@Composable
fun SkillsHeldSection(
    state: ImportedSkillsState,
    onPaste: () -> Unit,
    onRemove: (ImportedSkillData) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().testable("card_skills_held"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            localizedString("mobile.skill_held_title"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        when (state) {
            ImportedSkillsState.Loading ->
                StateBlock(ListState.Loading, tag = "skills_held_loading", inline = true)
            is ImportedSkillsState.Failed ->
                ReadFailureBlock(state.failure, tagPrefix = "skills_held", inline = true)
            is ImportedSkillsState.Loaded ->
                if (state.skills.isEmpty()) {
                    StateBlock(
                        ListState.Empty(localizedString("mobile.skill_held_empty")),
                        tag = "skills_held_empty",
                        inline = true,
                    )
                } else {
                    state.skills.forEach { skill ->
                        ImportedSkillCard(
                            skill = skill,
                            onDelete = { onRemove(skill) },
                            modifier = Modifier.testable("item_imported_skill_${skill.moduleName}"),
                        )
                    }
                }
        }
        OutlinedButton(
            onClick = onPaste,
            modifier = Modifier.fillMaxWidth().testableClickable("btn_skill_paste") { onPaste() },
        ) {
            Text(localizedString("mobile.skill_paste_door"))
        }
    }
}

/**
 * The three facts of removing an imported skill (CSD-015; ConfirmSheet rule):
 * which skill, what changes (its tools leave the agent; the adapter directory
 * is deleted, so bringing it back means importing it again), and who acts (the
 * signed-in admin — the agent route is ADMIN-gated; the agent records no
 * signature for it).
 */
@Composable
fun skillRemovalFacts(skill: ImportedSkillData): List<ConfirmFact> = listOf(
    ConfirmFact(localizedString("mobile.skill_remove_fact_skill"), skill.moduleName, mono = true),
    ConfirmFact(localizedString("mobile.skill_remove_fact_effect"), localizedString("mobile.skill_remove_effect")),
    ConfirmFact(localizedString("mobile.skill_remove_fact_actor"), localizedString("mobile.skill_remove_actor")),
)

@Composable
fun SkillRemovalSheet(
    skill: ImportedSkillData,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmSheet(
        title = localizedString("mobile.skill_delete_confirm", mapOf("name" to skill.originalSkillName.ifBlank { skill.moduleName })),
        facts = skillRemovalFacts(skill),
        confirmLabel = localizedString("mobile.skill_delete_title"),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
        tagPrefix = "skill_remove",
    )
}
