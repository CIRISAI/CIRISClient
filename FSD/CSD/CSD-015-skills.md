# CSD-015 — Skills (importing someone else's code into your agent)

**CSD**: CSD-015 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**Before a person gives their agent a skill somebody else wrote, they see what
it will be able to do, who scanned it, and what the scan found — and a scan
that did not answer is never read as a pass.** This is the only surface in the
client where third-party executable content crosses into the agent, so it is
the one place where the CC 3.1.2 provenance ladder has to be visible rather than
assumed. CC 3.4.5 puts `provenance:skill_import:{source}` in the
artifact-integrity class and states the reason it is not consent-gated: *a
forger never consents to verification.*

The falsifiable claim: **a green "SAFE TO IMPORT" banner means a verifier said
so.** It still fails on the attester half. The scan is run by the importing
agent itself (`routes/system/skill_import.py:385-421` on `main`,
`SkillSecurityScanner`), not by CIRISVerify, which CC 3.1.2 names as the owner
of the family — so the verdict is a self-report about a third party's code, with
no attester shown and no signature to check. That is CIRISAgent#1203 (OPEN).

The fail-open half is closed on both sides of the wire. The agent's
`SecurityReportResponse` now defaults `safe_to_import` to `False`
(`skill_import.py:71`, "fails closed (#1203)"), and the client matches: the
model default is `false` (`models/SkillImport.kt`), the wire parse reads an
absent field as `false` (`api/SkillImportWire.kt`), and a preview that carries
no report at all is drawn as an error with Import blocked — a scan nobody ran
is not a clear scan (`SkillPreviewData.importAllowed()`; `SkillImportWireTest`
pins all three).

## 2. Surface (what)

```yaml csd:surface
surface: skills
screen: SkillStudio
```

`nav_map` derives `btn_my_things -> nav_instrument_this_node ->
nav_epistemic_skills`. `agentOnly` (`CirclesNav.kt:155`) — correct: there is
nothing to import a skill into on a bare node.

**One card, two doors.** Until 2026-09-27 this was two Screens:
`Screen.SkillStudio` (write a skill here, then `validate` and `import`) and
`Screen.SkillImport` (paste a SKILL.md somebody else wrote, `preview`, then
`import`), both reached from the Adapters card, both ending in
`POST /v1/system/adapters/import-skill`. The route map named them a duplicate
pair, and this CSD described the paste door's routes as "never called" because
it only looked at one of the two. They are one act — a skill crossing into the
agent — with two ways of arriving at the SKILL.md, so `Screen.SkillImport` is
retired and its dialog (`SkillImportDialog`, `SkillImportViewModel`) is hosted
by `Screen.SkillStudio`, which now also lists what the agent already carries
(`SkillsHeldSection`) and lets the person remove a skill behind a ConfirmSheet.
`screenToSurface` maps the one screen to `NavSurface.Skills`; the Adapters
card's "Import skill" affordance opens the paste door on the Skills screen.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: provenance:skill_import:{source}
    bind: {source: clipboard}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "WHERE IT CAME FROM AND WHO VOUCHED — the draft carries a sourceUrl string; no attestation, no attesting key, no signature"
    tag: "proposed:skill_provenance"
    blocked_by: CIRISAgent#1203
  - ceg: x_private:security_safe_to_import
    use: display-only
    type: bool
    example: false
    renders: "SAFE TO IMPORT on green, or ISSUES FOUND on the error tone; absent means false. On the paste door the verdict carries `skill_security_verdict` (`safe` / `blocked`) and a preview with no report renders `skill_scan_missing` as an error"
    tag: skill_security_verdict
  - ceg: x_private:security_critical_count
    use: display-only
    type: int
    example: 0
    renders: "the Critical counter in the security report"
    tag: "proposed:skill_security_critical"
  - ceg: x_private:security_findings
    use: display-only
    type: "list[string]"
    example: ["shell invocation in tool `deploy`"]
    renders: "one row per finding, under the counters; on the paste door each is `skill_security_finding_{i}`"
    tag: skill_security_finding_0
  - ceg: x_private:skill_name
    use: emit
    type: string
    example: "weather-lookup"
    renders: "the metadata card's first field, and the name the imported module takes"
    tag: "proposed:skill_name"
  - ceg: x_private:skill_tools
    use: emit
    type: "list[string]"
    example: ["get_forecast", "get_alerts"]
    renders: "WHAT IT WILL BE ABLE TO DO — one card per tool the skill defines; `fab_add_tool` adds another"
    tag: fab_add_tool
  - ceg: x_private:skill_required_binaries
    use: display-only
    type: "list[string]"
    example: ["curl"]
    renders: "programs on the host this skill expects to run"
    tag: "proposed:skill_required_binaries"
  - ceg: x_private:tools_created
    use: display-only
    type: "list[string]"
    example: ["get_forecast", "get_alerts"]
    renders: "the success panel: what the agent gained, named"
    tag: btn_import_done
  - ceg: x_private:imported_skills
    use: display-only
    type: "list[string]"
    example: ["imported_weather_lookup"]
    renders: "SKILLS THIS AGENT CARRIES — one `item_imported_skill_{module}` row per skill from `GET /imported-skills`, each with `btn_delete_skill_{module}`"
    tag: card_skills_held
```

**The security-report strings on the Studio path are still hardcoded
English.** "Skill Studio", "Preview", "Security Report", "SAFE TO IMPORT" /
"ISSUES FOUND" and "Import" / "Blocked" (`SkillStudioScreen.kt`) are literals,
not `localizedString` keys. The paste door is localized (`mobile.skill_*`), and
the strings added in this pass (`mobile.skill_held_*`, `skill_scan_missing*`,
`skill_import_blocked`, `skill_remove_*`, `skill_paste_door`) are keys. The
Studio literals remain a client ask.

```yaml csd:states
populated: {tag: card_skills_held, renders: "the held list above the editor — metadata, tools, environment — with `btn_skill_preview` to the rendered SKILL.md and `btn_skill_paste` to the paste door"}
empty:     {tag: skills_held_empty, renders: "`GET /imported-skills` answered `[]`: 'This agent has no imported skills.' The editor is still offered"}
loading:   {tag: skills_held_loading, renders: "the held list's StateBlock spinner; the Studio's own Loading state (SkillStudioScreen.kt) is still untagged"}
error:     {tag: skills_held_error, renders: "the held list could not be read: StateBlock.Error, never the empty copy; `skills_held_not_on_this_node` when the host has no such route. The Studio's ErrorScreen (parse/validation) is still untagged"}
```

The held list is the first part of this card with all four states on distinct
tags; the Studio's own `Loading` / `Error` composables are distinct in code but
still carry no `testTag`.

**Removal is behind a ConfirmSheet** (`sheet_skill_remove`, facts
`skill_remove_fact_1..3`, `btn_skill_remove_confirm` / `_cancel`): which skill,
what changes (its tools leave the agent and the adapter directory is deleted),
and who acts (the signed-in admin — the route is ADMIN-gated, and the agent
records no signature for a removal). Removal is irreversible in the sense that
matters: the only way back is a fresh import, with a fresh scan.

## 3. Contracts (who)

Rows generated by `python3 packaging/check_csd_routes.py --print CSD-015` and annotated. All five routes are the agent's (`routes/system/skill_import.py` on `main` 29371660de), all `require_admin` (`:30`).

| value | endpoint | owner | state |
|---|---|---|---|
| validate + scan a draft | `POST /v1/system/adapters/import-skill/validate` | CIRISAgent (`skill_import.py:492`) | live — `validateSkill` (`CIRISApiClient.kt`), `SkillStudioViewModel.kt:498`. Response `SkillValidateResponse` (`:104-111`): `valid`, `errors[]`, `warnings[]`, `security` (required), `preview?`; parsed by `SkillImportWire` |
| preview a pasted SKILL.md | `POST /v1/system/adapters/import-skill/preview` | CIRISAgent (`:456`) | live — `previewSkillImport`, `SkillImportViewModel.kt:135`. Response `SkillPreviewResponse` (`:78-91`) INCLUDING `security` — the client parse dropped that field until this pass, so the paste door showed the tools and requirements and no scan |
| import it | `POST /v1/system/adapters/import-skill` | CIRISAgent (`:604`) | live — `importSkill`, from both doors (`SkillImportViewModel.kt:161`, `SkillStudioViewModel.kt:525`). The agent re-scans and answers **400** with `{message, findings[]}` when `safe_to_import` is false (`:640-652`); the client refuses the same before asking (`importAllowed()`), so the 400 is the backstop, not the UX. Request `SkillImportRequest` (`:38-45`); the client sends `skill_md_content`, `source_url?`, `auto_load` |
| what is already imported | `GET /v1/system/adapters/imported-skills` | CIRISAgent (`:685`) | live — `listImportedSkills`, `SkillImportViewModel.kt:87`, drawn by `SkillsHeldSection`. `ImportedSkillsListResponse` `{skills[], total}` (`:143-147`); `ImportedSkillInfo` (`:130-140`) maps field-for-field to `ImportedSkillData`. It scans `~/ciris/adapters/imported_*/manifest.json` with `metadata.imported_from == "openclaw"`, so a skill imported by another path is invisible here |
| remove one | `DELETE /v1/system/adapters/imported-skills/{module_name}` | CIRISAgent (`:740`) | live — `deleteImportedSkill`, `SkillImportViewModel.kt:199`, behind the ConfirmSheet. 404 when the name is not `imported_*` or the directory is gone; 500 when `rmtree` fails; the client now RAISES with the agent's reason instead of returning `false` |
| an attested import provenance | **no route** — `provenance:skill_import:{source}`, CC 3.1.2 | CIRISVerify | **missing**; blocks `building` for `proposed:skill_provenance`. `blocked_by: CIRISAgent#1203` |

**Host check.** All five routes are the brain's. `CIRISServer origin/main` has
no `/v1/system/adapters` literal in `src/`; its only `/v1/system/*` routes are
`health`, `data*` and `verify-status`. The card is `agentOnly`, so a bare node
never asks.

**Field check, preview/import.** `SkillPreviewResponse.tools` is always the
two-element `["skill:{name}", "skill:{name}:info"]` (`:397`), not the tools the
SKILL.md declares — the paste door's "What it can do" card therefore names the
wrapper tools, and the Studio's per-tool cards are the only place the declared
tools appear. `SkillImportResponse.tools_created` (`:122`) is the same pair.
Nothing is dropped on the client side any more: `SkillImportWire` parses every
field of all four response models.

**Wrong owner, stated plainly.** CC 3.1.2 assigns `provenance:skill_import:*`
to the `attestation` component, repo CIRISVerify. The scan that produces the
verdict this screen renders runs inside CIRISAgent. That is not a client bug,
but it is the reason the client has no attester to show, and a CSD that
rendered "SAFE TO IMPORT" without saying so would be asserting a verification
that nobody performed.

## 4. Flow (how)

My things → This node → Skills. The held list loads first.

```yaml
expect:
  visible: [card_skills_held, btn_skill_paste, btn_skill_preview, fab_add_tool]
  any_of: [skills_held_empty, item_imported_skill_*, skills_held_error, skills_held_not_on_this_node]
```

Paste door: `btn_skill_paste`, then `input_skill_md` a SKILL.md the scanner
clears, then `btn_skill_analyze`.

```yaml
expect:
  visible: [skill_security_verdict, btn_skill_import_confirm]
  count: {of: "skill_security_finding_", eq: 0}
```

`btn_skill_import_confirm`, then `btn_skill_import_done`; the held list refreshes.

```yaml
expect:
  visible: [item_imported_skill_imported_weather_lookup]
```

Paste a SKILL.md carrying a shell invocation, then `btn_skill_analyze`.

```yaml
expect:
  count: {of: "skill_security_finding_", min: 1}
  # btn_skill_import_confirm is present and DISABLED, labelled "Blocked by the scan"
```

Remove: `btn_delete_skill_imported_weather_lookup`.

```yaml
expect:
  visible: [sheet_skill_remove, skill_remove_fact_1, skill_remove_fact_2, skill_remove_fact_3]
```

`btn_skill_remove_confirm`; the row is gone and the list re-reads.

Studio door (unchanged): `btn_skill_preview` → `tab_skill_md`, `tab_security`,
`btn_copy_md` → `btn_import_now` → `btn_confirm_import` → `btn_import_done`.

## 5. QA plan

**Platforms.** All five. The editor is plain Compose; the clipboard paste path
differs per platform and is driven through the automation server rather than a
real clipboard (`input_skill_md`, `input_skill_source_url` have input sinks).

**Not tested here.** Whether an imported skill's tools actually run — that is
CSD-014's inventory, one refresh later. Whether the scanner catches any
particular class of hostile skill: the client asserts that findings are shown
and that the button is blocked, never that the scan is good.

**Closed in this pass (2026-09-27).** `Screen.SkillImport` folded into
`SkillStudio` (this CSD now describes what the code does); the paste door shows
the scan and fails closed; the held list and removal exist; the three response
parses share `SkillImportWire`; the tag collision between the two doors'
`btn_skill_preview` is resolved (`btn_skill_analyze` on the paste door);
`btn_show_source_url`, `btn_skill_import_confirm`, `btn_skill_import_done` are
`testableClickable`.

**Upstream asks.**
CIRISVerify / CIRISAgent — emit `provenance:skill_import:{source}` with an
attesting key on the import route, so the person is shown who vouched rather
than a colour (CIRISAgent#1203).
CIRISAgent — `SkillPreviewResponse.tools` names the wrapper pair, not the
declared tools; a person reviewing a third party's skill should see the tools
it declares.
CIRISClient — localize the Studio's security-report literals; tag the Studio's
own Loading and Error states.
