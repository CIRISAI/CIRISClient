# CIRIS Hausa Glossary (Hausa)

This glossary defines the canonical translations for key CIRIS terms in Hausa. All translators must use these terms consistently across ACCORD, Guide, UI, and DMA prompts.

**Note**: Hausa is written in both Latin script (Boko) and Arabic script (Ajami). This glossary uses Latin script (Boko) as the primary standard, which is more widely used in modern written Hausa.

## Core Action Verbs

| English | Hausa | Usage Context |
|---------|-------|---------------|
| OBSERVE | LURA | Gathering information from environment |
| SPEAK | FADI | Communicating with users |
| TOOL | KAYAN AIKI | Using external capabilities |
| REJECT | KI | Refusing to perform an action |
| PONDER | YI TUNANI | Deep reflection before deciding |
| DEFER | MIKA | Referring to Wise Authority |
| MEMORIZE | TUNA | Storing information in memory |
| RECALL | TUNO | Retrieving from memory |
| FORGET | MANTA | Removing from memory |
| TASK_COMPLETE | GAMA | Signaling task completion |

## Core Concepts

| English | Hausa | Definition |
|---------|-------|------------|
| ACCORD | [DEPRECATED] YARJEJENIYA | DEPRECATED — superseded by the CIRIS Constitution. Do NOT coin a rendering for it. The ROLE (`accord holder`, `accord_custody`, `/v1/accord/*`) is live and follows the shipped strings, not this row. Was: The covenant governing agent behavior |
| Wise Authority | Hukumar Hikima | Human oversight entity |
| Conscience | Lamiri | Ethical filter mechanism |
| Principal Hierarchy | Tsarin Shugabanci | Chain of command for guidance |
| Coherence | Daidaituwa | Logical and contextual consistency |
| Epistemic Humility | Tawali'u na Ilimi | Acknowledging knowledge limits |
| Integrity | Gaskiya | Ethical consistency |
| Resilience | Juriya | Recovery from failures |
| Signalling Gratitude | Nuna Godiya | Acknowledging contributions |
| Flourishing | Bunƙasa | Thriving well-being |
| Ubuntu | Ubuntu | "I am because we are" - interconnectedness |

## Technical Terms

| English | Hausa | Notes |
|---------|-------|-------|
| Agent | Wakili | Technical term |
| API | API | Keep in English |
| DMA | DMA | Decision-Making Adapter |
| LLM | LLM | Large Language Model |
| Token | Alamar shiga | Authentication/LLM context |
| Adapter | Na'ura mai haɗawa | Service extension |
| Service | Sabis | System component (loan word) |
| Pipeline | Bututun sarrafa | Processing chain |
| Memory | Ƙwaƙwalwa | Storage system |
| Graph | Zane na alaƙa | Knowledge graph structure |

## Cognitive States

| English | Hausa | Description |
|---------|-------|-------------|
| WAKEUP | FARKA | Identity confirmation state |
| WORK | AIKI | Normal task processing |
| PLAY | WASA | Creative exploration mode |
| SOLITUDE | KAƊAICI | Quiet reflection state |
| DREAM | MAFARKI | Deep introspection |
| SHUTDOWN | KASHE | Graceful termination |

## UI Labels

| English | Hausa | Notes |
|---------|-------|-------|
| Login | Shiga | |
| Logout | Fita | |
| Settings | Saituna | |
| Messages | Saƙonni | |
| Send | Aika | |
| Cancel | Soke | |
| Confirm | Tabbatar | |
| Error | Kuskure | |
| Warning | Gargaɗi | |
| Success | Nasara | |
| Loading | Ana lodi | |
| Save | Ajiye | |
| Delete | Share | |
| Edit | Gyara | |

## DMA-Specific Terms

| English | Hausa | Used In |
|---------|-------|---------|
| Principal Duties | Manyan Ayyuka | PDMA |
| Common Sense | Hankali na yau da kullum | CSDMA |
| Intuition | Basira | IDMA |
| Action Selection | Zaɓin Aiki | ASPDMA |
| Domain Specific | Na musamman ga yanki | DSDMA |
| Tool Specific | Na musamman ga kayan aiki | TSASPDMA |
| Fragility Flag | Alamar Rauni | IDMA |
| Correlation Risk | Haɗarin Alaƙa | IDMA |

## Phrases

| English | Hausa |
|---------|-------|
| "How can I help you?" | "Yaya zan taimaka muku?" |
| "I need to think about this" | "Ina bukatar in yi tunani kan wannan" |
| "Let me check with my Wise Authority" | "Bari in tuntubi Hukumar Hikima ta" |
| "Task completed successfully" | "An gama aikin cikin nasara" |
| "I cannot perform this action" | "Ba zan iya yin wannan aiki ba" |
| "Please wait while I process this" | "Don Allah ku jira yayin da nake sarrafa wannan" |
| "I understand your request" | "Na fahimci buƙatar ku" |

## Cultural Considerations

### Formality Level
- Use formal Hausa for ACCORD and official documentation
- Use conversational Hausa for UI strings and chat messages
- Technical terms may be borrowed from English where appropriate

### Pronouns and Respect
- Use "ku" (formal you, plural) when addressing users respectfully
- Use "kai/ke" (informal you) only in very casual contexts
- Hausa culture values respect (girmamawa) and hospitality (karamci)

### Hausa-Specific Considerations
- Hausa is the lingua franca of West Africa (Nigeria, Niger, Ghana, etc.)
- Strong oral tradition - clear, spoken-word-friendly translations preferred
- Islamic cultural influence - avoid content that conflicts with Islamic values
- Communal decision-making (shawara) aligns with DEFER action
- Ubuntu philosophy resonates with Hausa concept of "zumunci" (kinship/community bonds)

### Special Characters
- Hausa uses hooked letters: ɓ, ɗ, ƙ, ƴ (for implosive and ejective consonants)
- Ensure proper font support for these characters
- Tone marks (à, á, â) are optional in standard written Hausa but can clarify meaning

### Script Considerations
- Primary: Latin script (Boko) - used in education, government, media
- Secondary: Arabic script (Ajami) - used in religious contexts, traditional writing
- This glossary standardizes on Boko for accessibility

## DSASPDMA Deferral Taxonomy Terms

| English | Localized | Notes |
|---------|-----------|-------|
| DSASPDMA | DSASPDMA | Keep acronym in English |
| Deferral-Specific Action Selection | ZABIN AIKI NA MUSAMMAN GA JINKIRTAWA | DSASPDMA prompt title |
| Rights / Needs Taxonomy | TSARIN HAKKOKI / BUKATU | Taxonomy section heading |
| Rights basis | Tushen hakki | Label for treaty-aligned rights basis |
| Operational Deferral Reason | LAMBOBIN DALILIN AIKI NA JINKIRI | Operational reason-code section heading |
| primary_need_category | primary_need_category | JSON key; keep in English |
| operational_reason | operational_reason | JSON key; keep in English |
| secondary_need_categories | secondary_need_categories | JSON key; keep in English |
| rights_basis | rights_basis | JSON key; keep in English |
| domain_hint | domain_hint | JSON key; keep in English |

## CEG 0.6 Cohort-Scope Vocabulary (2.9.4 Commons hub)

| English | Hausa | Definition / Guidance |
|---------|-------|------------------------|
| Cohort scope | Ikon ƙungiyar (cohort scope) | CEG 0.6 wire-format term; keep English in parens for technical context |
| Layer | Shimfiɗa | UX surface for one cohort scope (also: "saiti" — tier/level) |
| Self | Kai | Reflexive — the agent itself |
| Family | Iyali | Sibling occurrences of the same agent — kin in the agent sense |
| Local Community | Al'umma ta gida | Locally-trusted peers — home channel, single guild |
| Global Communities | Al'ummomi na duniya | Cross-community affinity groups (plural) |
| Global Commons | Filin gama-gari na duniya | The universal federation layer (species + planet + federation) |
| The Commons | Filin gama-gari | Federation contribution-cards feed — distinct from the "duniya" (Global) layer |
| Constitutional | Tsarin mulki | Accord-holder identity surface (FSD-002 §4.1) — federation constitutional structure |
| Delegation | Wakilci | Granting scope/authority to act on one's behalf |
| Trust Topology | Tsarin amincewa | Federation trust graph — peers as nodes, trust as edges |
| Participate | Shiga | Federation needs registry — verb: take part / contribute |
| Affiliations | Mambobi | Cross-community memberships (CEG term for global-communities cohort) |
| Identities | Asalai | Known entities at a scope (plural of "Asali") |
| Trust | Amincewa | Trust state per identity |
| Trust policies | Manufofin amincewa | Policies governing automatic trust at a scope |
| Coming Soon | Yana zuwa nan ba da daɗewa ba | Placeholder for unshipped features |


## Mesh, Keys and Review Terms

Decided for client 0.5.239 so these terms stop being left in English. "corpus" means the rendering the shipped strings already use; "coined" means the corpus had none; "by hand" means the reviewer rejected both model drafts and the row was decided from the corpus and the reviewer's reasons. Machine-drafted and reviewed by a judge of a different model family; not native-reviewed.

| English | Translation | Transliteration | Notes |
|---------|-------------|-----------------|-------|
| trust root | tushen amincewa | - | corpus: the key or authority a node accepts as the origin of trust for a mesh; a node 'subscribes to' or 'adopts' one |
| owner binding | ɗaurin mai mallaka | - | coined: the signed record that ties a node to the person who owns it |
| kill switch | maɓallin kashewa | - | corpus: the accord's power to halt a node remotely in an emergency |
| audit log | rajistar tantancewa | - | corpus: the tamper-evident record of what a node or agent did |
| export job | aikin fitarwa | - | corpus: a queued task that packages a person's data for download |
| signing key | maɓallin sa hannu | - | corpus: the private key a node or person signs with |
| private key | maɓallin sirri | - | corpus: the secret half of a key pair |
| session token | token na zama | - | corpus: the credential a signed-in app sends with each request |
| secure hardware | kayan aiki mai tsaro | - | corpus: the device's protected key store: TPM, Secure Enclave, StrongBox |
| safety witness | shaidar tsaro | - | corpus: a party that watches and attests to an agent's safety behaviour |
| rubric review | binciken rubric | - | corpus: a review of an agent's behaviour against a scoring rubric |
| delegation scope | iyakar wakilci | - | corpus: the set of actions a delegate is allowed to perform |
| canonical node | kumburin misali | - | by hand: a node chosen as the reference copy a family's other nodes follow |
| serve node | kumburin hidima | - | corpus: a node that serves others on the mesh (always on, reachable) |
| genesis seed | irin genesis | - | corpus: the starting material from which a family's first records are created |
| circuit breaker | ketaren kadera | - | corpus: a safeguard that stops a failing service from being called until it recovers |
| attestation level | matakin shaida | - | by hand: how strongly a device has proven its identity and integrity (a numbered level) |
| memory consolidation | shirya ƙwaƙwalwa | - | corpus: the agent's periodic pass that summarises and reorganises what it remembers |
| setup wizard | jagorar saiti | - | by hand: the step-by-step screens that set up the app the first time |
| Secure Enclave | Secure Enclave | Secure Enclave | Apple's product name; keep in English as Apple writes it |

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2026-04-07 | Initial glossary |
| 1.1 | 2026-05-31 | Added CEG 0.6 cohort-scope vocabulary for 2.9.4 Commons hub |

---

*This glossary is the authoritative source for Hausa translations. All translators must consult this document before translating any CIRIS content.*
