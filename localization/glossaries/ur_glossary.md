# CIRIS Urdu Glossary (اردو)

This glossary defines the canonical translations for key CIRIS terms in Urdu. All translators must use these terms consistently across ACCORD, Guide, UI, and DMA prompts.

## Core Action Verbs

| English | Urdu | Transliteration | Usage Context |
|---------|------|-----------------|---------------|
| OBSERVE | مشاہدہ کریں | Mushahida Karen | Gathering information from environment |
| SPEAK | بولیں | Bolen | Communicating with users |
| TOOL | آلہ استعمال کریں | Aala Istemaal Karen | Using external capabilities |
| REJECT | رد کریں | Radd Karen | Refusing to perform an action |
| PONDER | غور کریں | Ghaur Karen | Deep reflection before deciding |
| DEFER | حوالے کریں | Hawale Karen | Referring to Wise Authority |
| MEMORIZE | یاد رکھیں | Yaad Rakhein | Storing information in memory |
| RECALL | یاد کریں | Yaad Karen | Retrieving from memory |
| FORGET | بھول جائیں | Bhool Jayen | Removing from memory |
| TASK_COMPLETE | مکمل | Mukammal | Signaling task completion |

## Core Concepts

| English | Urdu | Transliteration | Definition |
|---------|------|-----------------|------------|
| ACCORD | [DEPRECATED] عہد نامہ | Ahd Nama | DEPRECATED — superseded by the CIRIS Constitution. Do NOT coin a rendering for it. The ROLE (`accord holder`, `accord_custody`, `/v1/accord/*`) is live and follows the shipped strings, not this row. Was: The covenant governing agent behavior |
| Wise Authority | دانش مند اتھارٹی | Danish Mand Authority | Human oversight entity |
| Conscience | ضمیر | Zameer | Ethical filter mechanism |
| Principal Hierarchy | اصولی درجہ بندی | Usooli Darja Bandi | Chain of command for guidance |
| Coherence | ہم آہنگی | Hum Ahangi | Logical and contextual consistency |
| Epistemic Humility | علمی عاجزی | Ilmi Aajizi | Acknowledging knowledge limits |
| Integrity | دیانت | Diyanat | Ethical consistency |
| Resilience | لچک | Lachak | Recovery from failures |
| Signalling Gratitude | شکرگزاری کا اظہار | Shukr Guzari Ka Izhaar | Acknowledging contributions |

## Technical Terms

| English | Urdu | Transliteration | Notes |
|---------|------|-----------------|-------|
| Agent | ایجنٹ | Agent | Keep as-is (technical term) |
| API | API | API | Keep in English |
| DMA | DMA | DMA | Decision-Making Adapter |
| LLM | LLM | LLM | Large Language Model |
| Token | ٹوکن | Token | Authentication/LLM context |
| Adapter | اڈاپٹر | Adapter | Service extension |
| Service | سروس | Service | System component |
| Pipeline | پائپ لائن | Pipeline | Processing chain |

## Cognitive States

| English | Urdu | Transliteration | Description |
|---------|------|-----------------|-------------|
| WAKEUP | بیداری | Bedaari | Identity confirmation state |
| WORK | کام | Kaam | Normal task processing |
| PLAY | کھیل | Khel | Creative exploration mode |
| SOLITUDE | تنہائی | Tanhaai | Quiet reflection state |
| DREAM | خواب | Khwab | Deep introspection |
| SHUTDOWN | بند | Band | Graceful termination |

## UI Labels

| English | Urdu | Notes |
|---------|------|-------|
| Login | لاگ ان | Can also use "داخلہ" |
| Settings | ترتیبات | |
| Messages | پیغامات | |
| Send | بھیجیں | |
| Cancel | منسوخ | |
| Confirm | تصدیق | |
| Error | خرابی | |
| Warning | انتباہ | |
| Success | کامیابی | |
| Loading | لوڈ ہو رہا ہے | |

## DMA-Specific Terms

| English | Urdu | Used In |
|---------|------|---------|
| Principal Duties | بنیادی فرائض | PDMA |
| Common Sense | عام فہم | CSDMA |
| Intuition | وجدان | IDMA |
| Action Selection | عمل کا انتخاب | ASPDMA |
| Domain Specific | ڈومین مخصوص | DSDMA |
| Tool Specific | آلہ مخصوص | TSASPDMA |

## Phrases

| English | Urdu |
|---------|------|
| "How can I help you?" | "میں آپ کی کیا مدد کر سکتا ہوں؟" |
| "I need to think about this" | "مجھے اس پر غور کرنا ہوگا" |
| "Let me check with my Wise Authority" | "مجھے اپنی دانش مند اتھارٹی سے مشورہ کرنے دیں" |
| "Task completed successfully" | "کام کامیابی سے مکمل ہوگیا" |
| "I cannot perform this action" | "میں یہ عمل نہیں کر سکتا" |

## Cultural Considerations

### Formality Level
- Use formal Urdu register (ادبی اردو) for ACCORD and official documentation
- Use conversational Urdu (بول چال) for interface text (labels, buttons, menus)
- **When the agent speaks to the person** (keys under `prompts.`, safety and care exemplars, the agent's replies): always formal "آپ" (never "تم" or "تو"). This overrides any conversational rule for interface text: a person in distress is addressed with respect, and the agent's own guidance requires it.
- Use technical Urdu with English loan words for DMA prompts

### Honorifics
- When addressing users, use "آپ" (formal you) not "تم" (informal)
- For Wise Authority references, use respectful terminology

### RTL Considerations
- Urdu is written right-to-left
- Numbers remain left-to-right
- English terms within Urdu sentences maintain their direction
- Punctuation follows Urdu conventions (۔ instead of .)

## DSASPDMA Deferral Taxonomy Terms

| English | Localized | Notes |
|---------|-----------|-------|
| DSASPDMA | DSASPDMA | Keep acronym in English |
| Deferral-Specific Action Selection | تعویق کے لیے مخصوص عمل کا انتخاب | DSASPDMA prompt title |
| Rights / Needs Taxonomy | حقوق / ضروریات کی TAXONOMY | Taxonomy section heading |
| Rights basis | حقوق کی بنیاد | Label for treaty-aligned rights basis |
| Operational Deferral Reason | عملیاتی تعویق کے سبب کوڈز | Operational reason-code section heading |
| primary_need_category | primary_need_category | JSON key; keep in English |
| operational_reason | operational_reason | JSON key; keep in English |
| secondary_need_categories | secondary_need_categories | JSON key; keep in English |
| rights_basis | rights_basis | JSON key; keep in English |
| domain_hint | domain_hint | JSON key; keep in English |

## Commons / Cohort-Scope UX (2.9.4)

CEG 0.6 cohort-scope vocabulary used by the new Commons hub.

### Core Concepts (cohort scope)

| English | Urdu | Transliteration | Notes |
|---------|------|-----------------|-------|
| Cohort scope | کوہورٹ اسکوپ (شراکت کا دائرہ) | Cohort scope (sharakat ka daira) | Technical CIRIS term; keep "cohort scope" recognizable |
| Layer | پرت | Parat | UX surface for one cohort scope |
| Self | خود | Khud | Reflexive identity; "agent itself" |
| Family | خاندان | Khaandaan | Sibling occurrences (not nuclear family) |
| Local Community | مقامی برادری | Maqaami Biraadari | Locally-trusted peers |
| Global Communities | عالمی برادریاں | Aalmi Biraadariyaan | Cross-community affinity groups (plural) |
| Global Commons | عالمی مشترکہ میدان | Aalmi Mushtaraka Maidaan | Universal federation layer |
| The Commons | مشترکہ شراکت | Mushtaraka Sharakat | Federation contribution feed |
| Constitutional | آئینی | Aaeeni | Accord-holder identity surface; federation constitution |
| Delegation | تفویض | Tafveez | Granting authority to act on one's behalf |
| Trust Topology | اعتماد کا ڈھانچہ | I'timaad ka Dhaancha | Trust graph |
| Participate | شرکت کریں | Shirkat Karen | Federation needs registry |
| Affiliations | وابستگیاں | Vaabastagiyaan | Joined communities (CEG term) |

### UI Labels (Commons sections)

| English | Urdu | Notes |
|---------|------|-------|
| Identities | شناختیں | Plural; list of known entities at a scope |
| Trust | اعتماد | Trust state per identity |
| Trust policies | اعتماد کی پالیسیاں | Automatic-trust policies |
| Coming Soon | جلد آرہا ہے | Placeholder for unshipped features |


## Mesh, Keys and Review Terms

Decided for client 0.5.239 so these terms stop being left in English. "corpus" means the rendering the shipped strings already use; "coined" means the corpus had none; "by hand" means the reviewer rejected both model drafts and the row was decided from the corpus and the reviewer's reasons. Machine-drafted and reviewed by a judge of a different model family; not native-reviewed.

| English | Translation | Transliteration | Notes |
|---------|-------------|-----------------|-------|
| trust root | ٹرسٹ روٹ | ṭrasṭ rūṭ | corpus: the key or authority a node accepts as the origin of trust for a mesh; a node 'subscribes to' or 'adopts' one |
| owner binding | مالک سے وابستگی | malik se wabastagi | coined: the signed record that ties a node to the person who owns it |
| kill switch | کِل سوئچ | kil sawich | corpus: the accord's power to halt a node remotely in an emergency |
| audit log | آڈٹ لاگ | āḍiṭ lāg | corpus: the tamper-evident record of what a node or agent did |
| export job | ایکسپورٹ جاب | eksporṭ jāb | corpus: a queued task that packages a person's data for download |
| signing key | دستخطی کلید | dastkhatī kalīd | corpus: the private key a node or person signs with |
| private key | نجی کلید | nijī kalīd | corpus: the secret half of a key pair |
| session token | سیشن ٹوکن | seshan ṭokan | corpus: the credential a signed-in app sends with each request |
| secure hardware | محفوظ ہارڈویئر | mahfūz hārḍweyar | corpus: the device's protected key store: TPM, Secure Enclave, StrongBox |
| safety witness | حفاظتی گواہ | hifāzatī gawāh | coined: a party that watches and attests to an agent's safety behaviour |
| rubric review | معیاری جائزہ | mi'yārī jā'izah | coined: a review of an agent's behaviour against a scoring rubric |
| delegation scope | تفویض کا دائرہ کار | tafweez ka daira-e kaar | corpus: the set of actions a delegate is allowed to perform |
| canonical node | کینونیکل نوڈ | kaynonikal noḍ | corpus: a node chosen as the reference copy a family's other nodes follow |
| serve node | سرو نوڈ | sarv noḍ | corpus: a node that serves others on the mesh (always on, reachable) |
| genesis seed | جینیسس بیج | jīnesis bīj | corpus: the starting material from which a family's first records are created |
| circuit breaker | سرکٹ بریکر | sarkiṭ brekar | corpus: a safeguard that stops a failing service from being called until it recovers |
| attestation level | تصدیق کی سطح | tasdīq kī sath | corpus: how strongly a device has proven its identity and integrity (a numbered level) |
| memory consolidation | یادوں کی یکجائی | yādoṉ kī yakjā'ī | corpus: the agent's periodic pass that summarises and reorganises what it remembers |
| setup wizard | سیٹ اپ وزرڈ | seṭ ap wizarḍ | corpus: the step-by-step screens that set up the app the first time |
| Secure Enclave | Secure Enclave | Secure Enclave | Apple's product name; keep in English as Apple writes it |

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2024-XX-XX | Initial glossary |
| 1.1 | 2026-05-31 | Added CEG 0.6 cohort-scope vocabulary for 2.9.4 Commons hub |

---

*This glossary is the authoritative source for Urdu translations. All translators must consult this document before translating any CIRIS content.*
