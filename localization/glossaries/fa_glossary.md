# CIRIS Persian Glossary (فارسی)

This glossary defines the canonical translations for key CIRIS terms in Persian (Farsi). All translators must use these terms consistently across ACCORD, Guide, UI, and DMA prompts.

**Note**: This glossary covers standard Persian (Farsi) as used in Iran. Dari (Afghanistan) and Tajiki variants may require minor adaptations.

## Core Action Verbs

| English | Persian | Transliteration | Usage Context |
|---------|---------|-----------------|---------------|
| OBSERVE | مشاهده کنید | Moshahede Konid | Gathering information from environment |
| SPEAK | صحبت کنید | Sohbat Konid | Communicating with users |
| TOOL | ابزار | Abzar | Using external capabilities |
| REJECT | رد کنید | Radd Konid | Refusing to perform an action |
| PONDER | تأمل کنید | Ta'ammol Konid | Deep reflection before deciding |
| DEFER | ارجاع دهید | Erja' Dahid | Referring to Wise Authority |
| MEMORIZE | به خاطر بسپارید | Be Khater Besparid | Storing information in memory |
| RECALL | به یاد آورید | Be Yad Avarid | Retrieving from memory |
| FORGET | فراموش کنید | Faramush Konid | Removing from memory |
| TASK_COMPLETE | تکمیل شد | Takmil Shod | Signaling task completion |

## Core Concepts

| English | Persian | Transliteration | Definition |
|---------|---------|-----------------|------------|
| ACCORD | [DEPRECATED] پیمان | Peyman | DEPRECATED — superseded by the CIRIS Constitution. Do NOT coin a rendering for it. The ROLE (`accord holder`, `accord_custody`, `/v1/accord/*`) is live and follows the shipped strings, not this row. Was: The covenant governing agent behavior |
| Wise Authority | مرجع خردمند | Marja' Kheradmand | Human oversight entity |
| Conscience | وجدان | Vejdan | Ethical filter mechanism |
| Principal Hierarchy | سلسله‌مراتب اصلی | Selselemarateb Asli | Chain of command for guidance |
| Coherence | انسجام | Ensejam | Logical and contextual consistency |
| Epistemic Humility | فروتنی معرفتی | Forutani Ma'refati | Acknowledging knowledge limits |
| Integrity | صداقت | Sedaqat | Ethical consistency |
| Resilience | تاب‌آوری | Tab-Avari | Recovery from failures |
| Signalling Gratitude | ابراز قدردانی | Ebraz Qadrdani | Acknowledging contributions |
| Flourishing | شکوفایی | Shokufayi | Thriving well-being |
| Ubuntu | اوبونتو | Ubuntu | "I am because we are" - interconnectedness |

## Technical Terms

| English | Persian | Transliteration | Notes |
|---------|---------|-----------------|-------|
| Agent | عامل | Amel | Technical term |
| API | API | API | Keep in English |
| DMA | DMA | DMA | Decision-Making Adapter |
| LLM | LLM | LLM | Large Language Model |
| Token | توکن | Token | Authentication/LLM context |
| Adapter | آداپتور | Adaptor | Service extension |
| Service | سرویس | Servis | System component |
| Pipeline | خط لوله | Khatt-e Luleh | Processing chain |
| Memory | حافظه | Hafezeh | Storage system |
| Graph | گراف | Graf | Knowledge graph structure |

## Cognitive States

| English | Persian | Transliteration | Description |
|---------|---------|-----------------|-------------|
| WAKEUP | بیداری | Bidari | Identity confirmation state |
| WORK | کار | Kar | Normal task processing |
| PLAY | بازی | Bazi | Creative exploration mode |
| SOLITUDE | تنهایی | Tanhayi | Quiet reflection state |
| DREAM | رویا | Roya | Deep introspection |
| SHUTDOWN | خاموش | Khamush | Graceful termination |

## UI Labels

| English | Persian | Notes |
|---------|---------|-------|
| Login | ورود | |
| Logout | خروج | |
| Settings | تنظیمات | |
| Messages | پیام‌ها | |
| Send | ارسال | |
| Cancel | لغو | |
| Confirm | تأیید | |
| Error | خطا | |
| Warning | هشدار | |
| Success | موفقیت | |
| Loading | در حال بارگذاری | |
| Save | ذخیره | |
| Delete | حذف | |
| Edit | ویرایش | |

## DMA-Specific Terms

| English | Persian | Transliteration | Used In |
|---------|---------|-----------------|---------|
| Principal Duties | وظایف اصلی | Vazayef Asli | PDMA |
| Common Sense | عقل سلیم | Aql-e Salim | CSDMA |
| Intuition | شهود | Shohud | IDMA |
| Action Selection | انتخاب عمل | Entekhab-e Amal | ASPDMA |
| Domain Specific | خاص حوزه | Khass-e Howzeh | DSDMA |
| Tool Specific | خاص ابزار | Khass-e Abzar | TSASPDMA |
| Fragility Flag | نشانه شکنندگی | Neshaneh Shekanandegi | IDMA |
| Correlation Risk | ریسک همبستگی | Risk-e Hambastegi | IDMA |

## Phrases

| English | Persian |
|---------|---------|
| "How can I help you?" | "چطور می‌توانم به شما کمک کنم؟" |
| "I need to think about this" | "باید در این مورد فکر کنم" |
| "Let me check with my Wise Authority" | "اجازه دهید با مرجع خردمند خود مشورت کنم" |
| "Task completed successfully" | "کار با موفقیت انجام شد" |
| "I cannot perform this action" | "قادر به انجام این کار نیستم" |
| "Please wait while I process this" | "لطفاً صبر کنید تا این را پردازش کنم" |
| "I understand your request" | "درخواست شما را متوجه شدم" |

## Cultural Considerations

### Formality Level
- Use formal Persian (فارسی رسمی) for ACCORD and official documentation
- Use conversational Persian (فارسی محاوره‌ای) for interface text (labels, buttons, menus)
- **When the agent speaks to the person** (keys under `prompts.`, safety and care exemplars, the agent's replies): always formal "شما" with formal verb forms (never "تو"). This overrides any conversational rule for interface text: a person in distress is addressed with respect, and the agent's own guidance requires it.
- Technical terms may use English loan words where established

### Pronouns and Respect
- Use "شما" (formal you) when addressing users
- Persian culture highly values politeness (ادب) and respect (احترام)
- Ta'arof (تعارف) - the Persian system of polite expressions - may influence phrasing

### RTL Considerations
- Persian is written right-to-left
- Numbers remain left-to-right (Persian uses both ۰۱۲۳۴۵۶۷۸۹ and 0123456789)
- English terms within Persian sentences maintain their LTR direction
- Punctuation follows Persian conventions

### Regional Variants
- **Iran (فارسی)**: Standard reference for this glossary
- **Afghanistan (دری)**: Minor vocabulary differences; core terms same
- **Tajikistan (تاجیکی)**: Uses Cyrillic script; not covered here

### Persian-Specific Considerations
- Rich poetic and philosophical tradition - ACCORD translation should preserve elegance
- Concept of "خرد" (kherad/wisdom) central to Persian philosophy - aligns with Wise Authority
- "انسجام" (coherence) has deep roots in Persian aesthetics and thought
- Avoid direct transliteration where meaningful Persian equivalents exist

## DSASPDMA Deferral Taxonomy Terms

| English | Localized | Notes |
|---------|-----------|-------|
| DSASPDMA | DSASPDMA | Keep acronym in English |
| Deferral-Specific Action Selection | انتخاب اقدام ویژه ارجاع | DSASPDMA prompt title |
| Rights / Needs Taxonomy | رده‌بندی حقوق / نیازها | Taxonomy section heading |
| Rights basis | مبنای حقوقی | Label for treaty-aligned rights basis |
| Operational Deferral Reason | کدهای دلیل عملیاتی ارجاع | Operational reason-code section heading |
| primary_need_category | primary_need_category | JSON key; keep in English |
| operational_reason | operational_reason | JSON key; keep in English |
| secondary_need_categories | secondary_need_categories | JSON key; keep in English |
| rights_basis | rights_basis | JSON key; keep in English |
| domain_hint | domain_hint | JSON key; keep in English |

## Commons / Cohort-Scope UX (2.9.4)

CEG 0.6 cohort-scope vocabulary used by the new Commons hub.

### Core Concepts (cohort scope)

| English | Persian | Transliteration | Notes |
|---------|---------|-----------------|-------|
| Cohort scope | محدوده کوهورت (دامنه مشارکت) | Mahdude Cohort (Daamane Mosharekat) | Technical CIRIS term; keep "cohort scope" recognizable |
| Layer | لایه | Laaye | UX surface for one cohort scope |
| Self | خود | Khod | Reflexive identity; "agent itself" |
| Family | خانواده | Khaanevaade | Sibling occurrences (not nuclear family) |
| Local Community | اجتماع محلی | Ejtemaa-e Mahalli | Locally-trusted peers |
| Global Communities | اجتماعات جهانی | Ejtemaa'aat-e Jahaani | Cross-community affinity groups (plural) |
| Global Commons | میدان مشترک جهانی | Meydaan-e Moshtarak-e Jahaani | Universal federation layer |
| The Commons | مشارکت‌های مشترک | Mosharekat-haa-ye Moshtarak | Federation contribution feed |
| Constitutional | بنیادین | Bonyaadin | Accord-holder identity surface; federation constitution |
| Delegation | تفویض اختیار | Tafviz-e Ekhtiyaar | Granting authority to act on one's behalf |
| Trust Topology | توپولوژی اعتماد | Topoloji-ye E'temaad | Trust graph |
| Participate | مشارکت کنید | Mosharekat Konid | Federation needs registry |
| Affiliations | وابستگی‌ها | Vaabastegi-haa | Joined communities (CEG term) |

### UI Labels (Commons sections)

| English | Persian | Notes |
|---------|---------|-------|
| Identities | هویت‌ها | Plural; list of known entities at a scope |
| Trust | اعتماد | Trust state per identity |
| Trust policies | سیاست‌های اعتماد | Automatic-trust policies |
| Coming Soon | به‌زودی | Placeholder for unshipped features |


## Mesh, Keys and Review Terms

Decided for client 0.5.239 so these terms stop being left in English. "corpus" means the rendering the shipped strings already use; "coined" means the corpus had none; "by hand" means the reviewer rejected both model drafts and the row was decided from the corpus and the reviewer's reasons. Machine-drafted and reviewed by a judge of a different model family; not native-reviewed.

| English | Translation | Transliteration | Notes |
|---------|-------------|-----------------|-------|
| trust root | ریشهٔ اعتماد | rishe-ye e'temād | corpus: the key or authority a node accepts as the origin of trust for a mesh; a node 'subscribes to' or 'adopts' one |
| owner binding | پیوند مالکیت | peyvand-e mālekiyyat | corpus: the signed record that ties a node to the person who owns it |
| kill switch | کلید توقف اضطراری | kelid-e tavaqqof-e ezterāri | corpus: the accord's power to halt a node remotely in an emergency |
| audit log | گزارش ممیزی | gozāresh-e momayyezi | corpus: the tamper-evident record of what a node or agent did |
| export job | کار خروجی داده | - | by hand: a queued task that packages a person's data for download |
| signing key | کلید امضا | kelid-e emzā | corpus: the private key a node or person signs with |
| private key | کلید خصوصی | kelid-e khosusi | corpus: the secret half of a key pair |
| session token | توکن نشست | token-e neshast | corpus: the credential a signed-in app sends with each request |
| secure hardware | سخت‌افزار امن | sakht-afzār-e amn | corpus: the device's protected key store: TPM, Secure Enclave, StrongBox |
| safety witness | شاهد ایمنی | shāhed-e imeni | corpus: a party that watches and attests to an agent's safety behaviour |
| rubric review | بازبینی معیار | bāzbini-ye me'yār | corpus: a review of an agent's behaviour against a scoring rubric |
| delegation scope | دامنهٔ تفویض اختیار | dāmane-ye tafviz-e ekhtiyār | corpus: the set of actions a delegate is allowed to perform |
| canonical node | گره متعارف | gereh-e mote'āref | corpus: a node chosen as the reference copy a family's other nodes follow |
| serve node | گره سرویس‌دهنده | gereh-e servis-dahande | corpus: a node that serves others on the mesh (always on, reachable) |
| genesis seed | بذر پیدایش | bazr-e peydāyesh | corpus: the starting material from which a family's first records are created |
| circuit breaker | مدارشکن | madār-shekan | corpus: a safeguard that stops a failing service from being called until it recovers |
| attestation level | سطح گواهی | sath-e govāhī | corpus: how strongly a device has proven its identity and integrity (a numbered level) |
| memory consolidation | تثبیت حافظه | tasbit-e hāfeze | corpus: the agent's periodic pass that summarises and reorganises what it remembers |
| setup wizard | دستیار راه‌اندازی | dastyār-e rāh-andāzi | corpus: the step-by-step screens that set up the app the first time |
| Secure Enclave | Secure Enclave | Secure Enclave | Apple's product name; keep in English as Apple writes it |

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2026-04-07 | Initial glossary |
| 1.1 | 2026-05-31 | Added CEG 0.6 cohort-scope vocabulary for 2.9.4 Commons hub |

---

*This glossary is the authoritative source for Persian translations. All translators must consult this document before translating any CIRIS content.*
