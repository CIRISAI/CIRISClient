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

The falsifiable claim, and it fails today: **a green "SAFE TO IMPORT" banner
means a verifier said so.** Two things are true instead. The scan is run by the
importing agent itself (`routes/system/skill_import.py:513`,
`SkillSecurityScanner`), not by CIRISVerify, which CC 3.1.2 names as the owner
of the family — so the verdict is a self-report about a third party's code, with
no attester shown and no signature to check. And `safe_to_import` **defaults to
`true`** on both sides of the wire (`models/SkillImport.kt:33`;
`skill_import.py:71`), so a security envelope that arrives without the field
renders green and enables the Import button (`SkillStudioScreen.kt:497, :709`).
A security control whose default is "allow" is a control that fails open.

## 2. Surface (what)

```yaml csd:surface
surface: skills
screen: SkillStudio
```

`nav_map` derives `btn_my_things -> nav_instrument_this_node ->
nav_epistemic_skills`. `agentOnly` (`CirclesNav.kt:155`) — correct: there is
nothing to import a skill into on a bare node.

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
    example: true
    renders: "SAFE TO IMPORT on green, or ISSUES FOUND on the error tone — and TRUE IS THE DEFAULT when the field is absent"
    tag: "proposed:skill_security_verdict"
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
    renders: "one row per finding, under the counters"
    tag: "proposed:skill_security_finding"
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
```

**Every string on the security path is hardcoded English.** "Skill Studio"
(`SkillStudioScreen.kt:224`), "Preview" (`:361`), "Security Report" (`:462`),
"SAFE TO IMPORT" / "ISSUES FOUND" (`:709`) and "Import" / "Blocked" (`:500`)
are literals, not `localizedString` keys. This repo ships 29 bundles and keeps
them at parity with a check; the one screen where a person decides whether to
trust somebody else's code is the screen that speaks only English.

```yaml csd:states
populated: {tag: "proposed:skill_editing", renders: "the editing cards — metadata, tools, environment — with `btn_skill_preview` to the rendered SKILL.md"}
empty:     {tag: "proposed:skill_no_drafts", renders: "no draft open: the invitation to create one or paste a SKILL.md"}
loading:   {tag: "proposed:skill_loading", renders: "SkillStudioScreenState.Loading (SkillStudioScreen.kt:132) — a bare progress affordance"}
error:     {tag: "proposed:skill_error", renders: "ErrorScreen (SkillStudioScreen.kt:190) carrying the parse or validation message, with back to editing when a draft survives"}
```

`error` is a distinct composable reached from a distinct state, so it cannot be
confused with `empty` — this surface models its own states properly. What is
missing is the tags: not one of the seven states carries a `testTag`, so
automation can drive the buttons and cannot read which state it is in.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| validate + scan a SKILL.md | `POST /v1/system/adapters/import-skill/validate` | CIRISAgent (`routes/system/skill_import.py:492`) | live — `CIRISApiClient.kt:13439`; ADMIN role required |
| import it | `POST /v1/system/adapters/import-skill` | CIRISAgent (`routes/system/skill_import.py:604`) | live — `CIRISApiClient.kt:13533` |
| preview before committing | `POST /v1/system/adapters/import-skill/preview` | CIRISAgent (`routes/system/skill_import.py:456`) | live on the host; **the client never calls it** |
| what is already imported | `GET /v1/system/adapters/imported-skills` | CIRISAgent (`routes/system/skill_import.py:685`) | live on the host; **the client never calls it** — so the screen cannot list the skills the agent already has |
| an attested import provenance | **no route** — `provenance:skill_import:{source}`, CC 3.1.2 | CIRISVerify | **missing**; blocks `building` for `proposed:skill_provenance` |

**Host check.** All four routes are the brain's. `CIRISServer origin/main` has
no `/v1/system/adapters` literal in `src/`; its only `/v1/system/*` routes are
`health`, `data*` and `verify-status`.

**CIRISAgent working tree is dated 2026-08-15**, five weeks behind today.

**Wrong owner, stated plainly.** CC 3.1.2 assigns `provenance:skill_import:*`
to the `attestation` component, repo CIRISVerify. The scan that produces the
verdict this screen renders runs inside CIRISAgent. That is not a client bug,
but it is the reason the client has no attester to show, and a CSD that
rendered "SAFE TO IMPORT" without saying so would be asserting a verification
that nobody performed.

## 4. Flow (how)

My things → This node → Skills. Paste a SKILL.md that the scanner clears.

```yaml
expect:
  visible: [btn_skill_preview, fab_add_tool]
```

Preview it, then open the security tab.

```yaml
expect:
  visible: [tab_skill_md, tab_security, btn_copy_md]
```

Import: `btn_import_now`, then `btn_confirm_import`.

```yaml
expect:
  visible: [btn_import_done]
  count: {of: "proposed:skill_security_finding", eq: 0}
```

Paste a SKILL.md carrying a shell invocation.

```yaml
expect:
  count: {of: "proposed:skill_security_finding", min: 1}
  absent: [btn_confirm_import]
```

## 5. QA plan

**Platforms.** All five. The editor is plain Compose; the clipboard paste path
differs per platform and is driven through the automation server rather than a
real clipboard.

**Not tested here.** Whether an imported skill's tools actually run — that is
CSD-014's inventory, one refresh later. Whether the scanner catches any
particular class of hostile skill: the client asserts that findings are shown
and that the button is blocked, never that the scan is good.

**Upstream asks.**
CIRISVerify / CIRISAgent — emit `provenance:skill_import:{source}` with an
attesting key on the import route, so the person is shown who vouched rather
than a colour.
CIRISAgent — make `safe_to_import` required on the wire
(`skill_import.py:71` defaults it `True`); a security verdict must not have a
default.
CIRISClient — remove the same default at `models/SkillImport.kt:33`, localize
the security-report strings, and call `GET /v1/system/adapters/imported-skills`
so a person can see what their agent already carries.
