# CIRIS Arabic Glossary (العربية)

This glossary defines the canonical translations for key CIRIS terms in Arabic. All translators must use these terms consistently across ACCORD, Guide, UI, and DMA prompts.

## Core Action Verbs

| English | Arabic | Transliteration | Usage Context |
|---------|--------|-----------------|---------------|
| OBSERVE | مراقبة | Muraqaba | Gathering information from environment |
| SPEAK | تحدث | Tahadduth | Communicating with users |
| TOOL | أداة | Adah | Using external capabilities |
| REJECT | رفض | Rafd | Refusing to perform an action |
| PONDER | تأمل | Ta'ammul | Deep reflection before deciding |
| DEFER | تأجيل | Ta'jeel | Referring to Wise Authority |
| MEMORIZE | حفظ | Hifz | Storing information in memory |
| RECALL | استذكار | Istidhkar | Retrieving from memory |
| FORGET | نسيان | Nisyan | Removing from memory |
| TASK_COMPLETE | اكتملت المهمة | Iktamalat al-Muhimmah | Signaling task completion |

## Core Concepts

| English | Arabic | Transliteration | Definition |
|---------|--------|-----------------|------------|
| ACCORD | [DEPRECATED] عہد نامہ | Ahd Namah | DEPRECATED — superseded by the CIRIS Constitution. Do NOT coin a rendering for it. The ROLE (`accord holder`, `accord_custody`, `/v1/accord/*`) is live and follows the shipped strings, not this row. Was: The covenant governing agent behavior |
| Wise Authority | السلطة الحكيمة | As-Sultah al-Hakimah | Human oversight entity |
| Conscience | الضمير | Ad-Damir | Ethical filter mechanism |
| Principal Hierarchy | التسلسل الهرمي للمبادئ | At-Tasalsul al-Harami lil-Mabadi' | Chain of command for guidance |
| Coherence | التماسك | At-Tamasuk | Logical and contextual consistency |
| Epistemic Humility | التواضع المعرفي | At-Tawadu' al-Ma'rifi | Acknowledging knowledge limits |
| Integrity | النزاهة | An-Nazahah | Ethical consistency |
| Resilience | المرونة | Al-Marunah | Recovery from failures |
| Signalling Gratitude | إظهار الامتنان | Izhar al-Imtinan | Acknowledging contributions |

## Technical Terms

| English | Arabic | Transliteration | Notes |
|---------|--------|-----------------|-------|
| Agent | وكيل | Wakil | AI agent/representative |
| API | API | API | Keep in English |
| DMA | DMA | DMA | Decision-Making Adapter |
| LLM | LLM | LLM | Large Language Model |
| Token | رمز | Ramz | Authentication/LLM context |
| Adapter | محول | Muhawwil | Service extension |
| Service | خدمة | Khidmah | System component |
| Pipeline | خط أنابيب | Khatt Anabib | Processing chain |

## Cognitive States

| English | Arabic | Transliteration | Description |
|---------|--------|-----------------|-------------|
| WAKEUP | استيقاظ | Istiqadh | Identity confirmation state |
| WORK | عمل | 'Amal | Normal task processing |
| PLAY | لعب | La'ib | Creative exploration mode |
| SOLITUDE | عزلة | 'Uzlah | Quiet reflection state |
| DREAM | حلم | Hilm | Deep introspection |
| SHUTDOWN | إيقاف | Iqaf | Graceful termination |

## UI Labels

| English | Arabic | Notes |
|---------|--------|-------|
| Login | تسجيل الدخول | |
| Settings | الإعدادات | |
| Messages | الرسائل | |
| Send | إرسال | |
| Cancel | إلغاء | |
| Confirm | تأكيد | |
| Error | خطأ | |
| Warning | تحذير | |
| Success | نجاح | |
| Loading | جارٍ التحميل | |

## DMA-Specific Terms

| English | Arabic | Used In |
|---------|--------|---------|
| Principal Duties | الواجبات الأساسية | PDMA |
| Common Sense | الحس السليم | CSDMA |
| Intuition | الحدس | IDMA |
| Action Selection | اختيار الإجراء | ASPDMA |
| Domain Specific | خاص بالمجال | DSDMA |
| Tool Specific | خاص بالأداة | TSASPDMA |

## Processing States

| English | Arabic |
|---------|--------|
| "Processing..." | "جارٍ المعالجة..." |
| "Thinking..." | "جارٍ التفكير..." |
| "Speaking..." | "جارٍ التحدث..." |
| "Memorizing..." | "الحفظ في الذاكرة..." |
| "Recalling..." | "جارٍ الاستذكار..." |
| "Using tool..." | "استخدام الأداة..." |
| "Pondering..." | "جارٍ التأمل..." |
| "Gathering context..." | "جمع السياق..." |
| "Evaluating..." | "جارٍ التقييم..." |
| "Selecting action" | "اختيار الإجراء" |

## Phrases

| English | Arabic |
|---------|--------|
| "How can I help you today?" | "كيف يمكنني مساعدتك اليوم؟" |
| "I need to think about this" | "دعني أفكر في ذلك..." |
| "I need to consult my Wise Authority" | "أحتاج إلى استشارة السلطة الحكيمة في هذا الأمر. سأعود إليك." |
| "Task completed successfully" | "اكتملت المهمة بنجاح." |
| "I don't have permission to do that" | "ليس لدي إذن للقيام بذلك." |
| "Can you clarify what you mean?" | "هل يمكنك توضيح ما تقصد؟" |
| "All systems operational" | "جميع الأنظمة تعمل" |
| "Waiting for agent..." | "في انتظار الوكيل..." |

## H3ERE Pipeline Terms

| English | Arabic |
|---------|--------|
| "Start thinking" | "بدء التفكير" |
| "Gather context" | "جمع السياق" |
| "Decision-making" | "صنع القرار" |
| "Intuition check" | "فحص الحدس" |
| "Action selection" | "اختيار الإجراء" |
| "Ethics check" | "فحص الأخلاق" |
| "Execute action" | "تنفيذ الإجراء" |
| "Memory graph" | "رسم الذاكرة" |

## Cultural Considerations

### Formality Level
- Use formal Modern Standard Arabic (الفصحى الحديثة) for ACCORD and official documentation
- Use conversational Arabic for UI strings and chat messages
- Technical terms may remain in English when widely understood (API, LLM, DMA)

### Honorifics
- When addressing users, use formal register
- For Wise Authority references, use respectful terminology (السلطة الحكيمة)
- Maintain professional tone in all communications

### RTL Considerations
- Arabic is written right-to-left (RTL)
- Numbers remain left-to-right (LTR)
- English terms within Arabic sentences maintain their direction
- Punctuation follows Arabic conventions
- UI layouts must accommodate RTL flow
- Text alignment should be right-aligned for Arabic content

### Script and Typography
- Use clear, legible Arabic fonts suitable for digital interfaces
- Ensure proper diacritical mark handling when needed
- Consider letter connecting forms (initial, medial, final, isolated)

## DSASPDMA Deferral Taxonomy Terms

| English | Localized | Notes |
|---------|-----------|-------|
| DSASPDMA | DSASPDMA | Keep acronym in English |
| Deferral-Specific Action Selection | اختيار الإجراء الخاص بالإحالة | DSASPDMA prompt title |
| Rights / Needs Taxonomy | تصنيف الحقوق / الاحتياجات | Taxonomy section heading |
| Rights basis | أساس الحقوق | Label for treaty-aligned rights basis |
| Operational Deferral Reason | رموز سبب الإحالة التشغيلية | Operational reason-code section heading |
| primary_need_category | primary_need_category | JSON key; keep in English |
| operational_reason | operational_reason | JSON key; keep in English |
| secondary_need_categories | secondary_need_categories | JSON key; keep in English |
| rights_basis | rights_basis | JSON key; keep in English |
| domain_hint | domain_hint | JSON key; keep in English |

## CEG 0.6 Cohort-Scope Vocabulary (2.9.4 Commons hub)

| English | Arabic | Transliteration | Definition / Guidance |
|---------|--------|-----------------|------------------------|
| Cohort scope | نطاق المجموعة (cohort scope) | Niṭāq al-Majmū'ah | CEG 0.6 wire-format term; keep English in parentheses when used in technical text |
| Layer | طبقة | Ṭabaqah | UX surface for one cohort scope |
| Self | الذات | Adh-Dhāt | Reflexive "self" referring to the agent itself |
| Family | العائلة | Al-'Ā'ilah | Sibling occurrences of the same agent — not nuclear-family connotation |
| Local Community | المجتمع المحلي | Al-Mujtama' al-Maḥalliy | Locally-trusted peers — home channel, household, single guild |
| Global Communities | المجتمعات العالمية | Al-Mujtama'āt al-'Ālamiyyah | Cross-community affinity groups (CEG: affiliations) |
| Global Commons | المشاع العالمي | Al-Mushā' al-'Ālamiy | The universal federation layer (species + planet + federation) |
| The Commons | المشاع | Al-Mushā' | Federation contribution-cards screen — distinct from "Global Commons" the layer |
| Constitutional | الدستوري | Ad-Dustūriy | Accord-holder identity surface (FSD-002 §4.1) — refers to federation constitutional structure |
| Delegation | التفويض | At-Tafwīḍ | Granting authority/scope to act on one's behalf |
| Trust Topology | طوبولوجيا الثقة | Ṭūbūlūjiyā ath-Thiqah | Federation trust graph — peers as nodes, trust grants as edges |
| Participate | المشاركة | Al-Mushārakah | Federation needs registry — verb form: take part / contribute |
| Affiliations | الانتسابات | Al-Intisābāt | Cross-community memberships (CEG term for global-communities cohort) |
| Identities | الهويات | Al-Huwiyyāt | Known entities at a scope, with friendly names where available |
| Trust | الثقة | Ath-Thiqah | Trust state per identity |
| Trust policies | سياسات الثقة | Siyāsāt ath-Thiqah | Policies governing automatic trust at a scope |
| Coming Soon | قريباً | Qarīban | Placeholder for unshipped features |


## Mesh, Keys and Review Terms

Decided for client 0.5.239 so these terms stop being left in English. "corpus" means the rendering the shipped strings already use; "coined" means the corpus had none; "by hand" means the reviewer rejected both model drafts and the row was decided from the corpus and the reviewer's reasons. Machine-drafted and reviewed by a judge of a different model family; not native-reviewed.

| English | Translation | Transliteration | Notes |
|---------|-------------|-----------------|-------|
| trust root | جذر الثقة | jadhr al-thiqa | corpus: the key or authority a node accepts as the origin of trust for a mesh; a node 'subscribes to' or 'adopts' one |
| owner binding | ربط المالك | rabt al-malik | corpus: the signed record that ties a node to the person who owns it |
| kill switch | مفتاح الإيقاف | miftah al-iqaf | corpus: the accord's power to halt a node remotely in an emergency |
| audit log | سجل المراجعة | sijill al-murāja'ah | corpus: the tamper-evident record of what a node or agent did |
| export job | مهمة التصدير | mahammat al-tasdir | corpus: a queued task that packages a person's data for download |
| signing key | مفتاح التوقيع | miftah al-tawqi' | corpus: the private key a node or person signs with |
| private key | المفتاح الخاص | al-miftah al-khass | corpus: the secret half of a key pair |
| session token | رمز الجلسة | ramz al-jalsa | corpus: the credential a signed-in app sends with each request |
| secure hardware | العتاد الآمن | al-'atad al-amin | corpus: the device's protected key store: TPM, Secure Enclave, StrongBox |
| safety witness | مراقب سلامة | - | by hand: a party that watches and attests to an agent's safety behaviour |
| rubric review | مراجعة رُبريك | muraja'at rubrik | corpus: a review of an agent's behaviour against a scoring rubric |
| delegation scope | نطاق التفويض | nitaq al-tafwid | corpus: the set of actions a delegate is allowed to perform |
| canonical node | العقدة المرجعية | al-'uqda al-marji'iyya | corpus: a node chosen as the reference copy a family's other nodes follow |
| serve node | عقدة خدمة | 'uqdat khidmah | corpus: a node that serves others on the mesh (always on, reachable) |
| genesis seed | بذرة التأسيس | badhrat al-ta'sis | corpus: the starting material from which a family's first records are created |
| circuit breaker | قاطع الدائرة | qati' al-da'ira | corpus: a safeguard that stops a failing service from being called until it recovers |
| attestation level | مستوى التصديق | mustawā al-taṣdīq | corpus: how strongly a device has proven its identity and integrity (a numbered level) |
| memory consolidation | دمج الذاكرة | damj al-dhakira | corpus: the agent's periodic pass that summarises and reorganises what it remembers |
| setup wizard | معالج الإعداد | mu'alij al-i'dad | corpus: the step-by-step screens that set up the app the first time |
| Secure Enclave | Secure Enclave | Secure Enclave | Apple's product name; keep in English as Apple writes it |

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2026-03-27 | Initial glossary extracted from ar.json |
| 1.1 | 2026-05-31 | Added CEG 0.6 cohort-scope vocabulary for 2.9.4 Commons hub |

---

*This glossary is the authoritative source for Arabic translations. All translators must consult this document before translating any CIRIS content.*
