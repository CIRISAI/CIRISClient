# CIRIS Indonesian Glossary (Bahasa Indonesia)

This glossary defines the canonical translations for key CIRIS terms in Indonesian. All translators must use these terms consistently across ACCORD, Guide, UI, and DMA prompts.

## Core Action Verbs

| English | Indonesian | Usage Context |
|---------|------------|---------------|
| OBSERVE | AMATI | Gathering information from environment |
| SPEAK | BICARA | Communicating with users |
| TOOL | ALAT | Using external capabilities |
| REJECT | TOLAK | Refusing to perform an action |
| PONDER | RENUNGKAN | Deep reflection before deciding |
| DEFER | SERAHKAN | Referring to Wise Authority |
| MEMORIZE | INGAT | Storing information in memory |
| RECALL | PANGGIL | Retrieving from memory |
| FORGET | LUPAKAN | Removing from memory |
| TASK_COMPLETE | SELESAI | Signaling task completion |

## Core Concepts

| English | Indonesian | Definition |
|---------|------------|------------|
| ACCORD | [DEPRECATED] PERJANJIAN | DEPRECATED — superseded by the CIRIS Constitution. Do NOT coin a rendering for it. The ROLE (`accord holder`, `accord_custody`, `/v1/accord/*`) is live and follows the shipped strings, not this row. Was: The covenant governing agent behavior |
| Wise Authority | Otoritas Bijak | Human oversight entity |
| Conscience | Nurani | Ethical filter mechanism |
| Principal Hierarchy | Hierarki Prinsip | Chain of command for guidance |
| Coherence | Koherensi | Logical and contextual consistency |
| Epistemic Humility | Kerendahan Hati Epistemik | Acknowledging knowledge limits |
| Integrity | Integritas | Ethical consistency |
| Resilience | Ketahanan | Recovery from failures |
| Signalling Gratitude | Ungkapan Syukur | Acknowledging contributions |
| Flourishing | Berkembang | Thriving well-being |
| Ubuntu | Ubuntu | "I am because we are" - interconnectedness |

## Technical Terms

| English | Indonesian | Notes |
|---------|------------|-------|
| Agent | Agen | Technical term, widely understood |
| API | API | Keep in English |
| DMA | DMA | Decision-Making Adapter |
| LLM | LLM | Large Language Model |
| Token | Token | Authentication/LLM context |
| Adapter | Adaptor | Service extension |
| Service | Layanan | System component |
| Pipeline | Pipa | Processing chain |
| Memory | Memori | Storage system |
| Graph | Graf | Knowledge graph structure |

## Cognitive States

| English | Indonesian | Description |
|---------|------------|-------------|
| WAKEUP | BANGUN | Identity confirmation state |
| WORK | KERJA | Normal task processing |
| PLAY | BERMAIN | Creative exploration mode |
| SOLITUDE | KESENDIRIAN | Quiet reflection state |
| DREAM | MIMPI | Deep introspection |
| SHUTDOWN | MATIKAN | Graceful termination |

## UI Labels

| English | Indonesian | Notes |
|---------|------------|-------|
| Login | Masuk | |
| Logout | Keluar | |
| Settings | Pengaturan | |
| Messages | Pesan | |
| Send | Kirim | |
| Cancel | Batal | |
| Confirm | Konfirmasi | |
| Error | Kesalahan | |
| Warning | Peringatan | |
| Success | Berhasil | |
| Loading | Memuat | |
| Save | Simpan | |
| Delete | Hapus | |
| Edit | Ubah | |

## DMA-Specific Terms

| English | Indonesian | Used In |
|---------|------------|---------|
| Principal Duties | Tugas Utama | PDMA |
| Common Sense | Akal Sehat | CSDMA |
| Intuition | Intuisi | IDMA |
| Action Selection | Pemilihan Tindakan | ASPDMA |
| Domain Specific | Khusus Domain | DSDMA |
| Tool Specific | Khusus Alat | TSASPDMA |
| Fragility Flag | Penanda Kerentanan | IDMA |
| Correlation Risk | Risiko Korelasi | IDMA |

## Phrases

| English | Indonesian |
|---------|------------|
| "How can I help you?" | "Bagaimana saya dapat membantu Anda?" |
| "I need to think about this" | "Saya perlu memikirkan ini" |
| "Let me check with my Wise Authority" | "Izinkan saya berkonsultasi dengan Otoritas Bijak" |
| "Task completed successfully" | "Tugas berhasil diselesaikan" |
| "I cannot perform this action" | "Saya tidak dapat melakukan tindakan ini" |
| "Please wait while I process this" | "Mohon tunggu sementara saya memproses ini" |
| "I understand your request" | "Saya memahami permintaan Anda" |

## Cultural Considerations

### Formality Level
- Use formal Indonesian (Bahasa Indonesia baku) for ACCORD and official documentation
- Use conversational Indonesian for interface text (labels, buttons, menus)
- **When the agent speaks to the person** (keys under `prompts.`, safety and care exemplars, the agent's replies): always formal "Anda" (never "kamu" or "kau"). This overrides any conversational rule for interface text: a person in distress is addressed with respect, and the agent's own guidance requires it.
- Technical terms may be borrowed from English where no clear Indonesian equivalent exists

### Pronouns
- Use "Anda" (formal you) when addressing users
- Use "saya" (I) for agent self-reference
- Avoid overly casual pronouns like "kamu" or "gue"

### Indonesian-Specific Considerations
- Indonesia has the world's largest Muslim population; be culturally sensitive
- Gotong royong (mutual cooperation) aligns with Ubuntu philosophy
- Musyawarah (consensus through deliberation) reflects DEFER action well
- Numbers use Western format (123.456,78 for thousands separator is comma, decimal is period in formal contexts, though European style also used)

### Loan Words
- Many technical terms use English loan words (adopsi, adaptasi, implementasi)
- Maintain consistency with established Indonesian technical vocabulary
- When in doubt, prefer official KBBI (Kamus Besar Bahasa Indonesia) terms

## DSASPDMA Deferral Taxonomy Terms

| English | Localized | Notes |
|---------|-----------|-------|
| DSASPDMA | DSASPDMA | Keep acronym in English |
| Deferral-Specific Action Selection | PEMILIHAN TINDAKAN KHUSUS DEFERENSI | DSASPDMA prompt title |
| Rights / Needs Taxonomy | TAKSONOMI HAK / KEBUTUHAN | Taxonomy section heading |
| Rights basis | Dasar hak | Label for treaty-aligned rights basis |
| Operational Deferral Reason | KODE ALASAN OPERASIONAL DEFERENSI | Operational reason-code section heading |
| primary_need_category | primary_need_category | JSON key; keep in English |
| operational_reason | operational_reason | JSON key; keep in English |
| secondary_need_categories | secondary_need_categories | JSON key; keep in English |
| rights_basis | rights_basis | JSON key; keep in English |
| domain_hint | domain_hint | JSON key; keep in English |

## CEG 0.6 Cohort Scope Vocabulary (2.9.4 Commons UX)

| English | Indonesian | Definition |
|---------|------------|------------|
| Cohort scope | Cakupan kohort (cohort scope) | CEG 0.6 wire term — scale at which a contribution applies |
| Layer | Lapisan | UX surface for one cohort scope |
| Agent (Self) | Agen (Diri) | The agent itself; first layer |
| Family | Keluarga | Sibling occurrences of the same agent |
| Local Community | Komunitas lokal | Locally-trusted peers |
| Global Communities | Komunitas global | Cross-community affinity groups (affiliations) |
| Global Commons | Ranah bersama global | Universal federation layer |
| The Commons | Ranah bersama (umpan kontribusi) | Federation contribution cards screen |
| Constitutional | Konstitusional (federasi) | Accord-holder identity surface per FSD-002 §4.1 |
| Delegation | Delegasi | Delegation graph |
| Trust Topology | Topologi kepercayaan | Federation trust graph |
| Participate | Berpartisipasi | Federation needs registry |
| Identities | Identitas | List of known entities at a scope |
| Trust | Kepercayaan | Trust state per identity |
| Trust policies | Kebijakan kepercayaan | Policies governing automatic trust |
| Coming Soon | Segera hadir | Placeholder for unshipped features |


## Mesh, Keys and Review Terms

Decided for client 0.5.239 so these terms stop being left in English. "corpus" means the rendering the shipped strings already use; "coined" means the corpus had none; "by hand" means the reviewer rejected both model drafts and the row was decided from the corpus and the reviewer's reasons. Machine-drafted and reviewed by a judge of a different model family; not native-reviewed.

| English | Translation | Transliteration | Notes |
|---------|-------------|-----------------|-------|
| trust root | akar kepercayaan | - | corpus: the key or authority a node accepts as the origin of trust for a mesh; a node 'subscribes to' or 'adopts' one |
| owner binding | ikatan kepemilikan | - | coined: the signed record that ties a node to the person who owns it |
| kill switch | sakelar pemutus | - | corpus: the accord's power to halt a node remotely in an emergency |
| audit log | log audit | - | corpus: the tamper-evident record of what a node or agent did |
| export job | tugas ekspor | - | coined: a queued task that packages a person's data for download |
| signing key | kunci penandatanganan | - | corpus: the private key a node or person signs with |
| private key | kunci privat | - | corpus: the secret half of a key pair |
| session token | token sesi | - | corpus: the credential a signed-in app sends with each request |
| secure hardware | perangkat keras aman | - | corpus: the device's protected key store: TPM, Secure Enclave, StrongBox |
| safety witness | saksi keselamatan | - | coined: a party that watches and attests to an agent's safety behaviour |
| rubric review | tinjauan berbasis rubrik | - | coined: a review of an agent's behaviour against a scoring rubric |
| delegation scope | cakupan delegasi | - | corpus: the set of actions a delegate is allowed to perform |
| canonical node | node kanonik | - | corpus: a node chosen as the reference copy a family's other nodes follow |
| serve node | node pelayan | - | by hand: a node that serves others on the mesh (always on, reachable) |
| genesis seed | seed genesis | - | corpus: the starting material from which a family's first records are created |
| circuit breaker | pemutus sirkuit | - | coined: a safeguard that stops a failing service from being called until it recovers |
| attestation level | tingkat atestasi | - | corpus: how strongly a device has proven its identity and integrity (a numbered level) |
| memory consolidation | konsolidasi memori | - | corpus: the agent's periodic pass that summarises and reorganises what it remembers |
| setup wizard | wizard penyiapan | - | corpus: the step-by-step screens that set up the app the first time |
| Secure Enclave | Secure Enclave | Secure Enclave | Apple's product name; keep in English as Apple writes it |

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2026-04-07 | Initial glossary |

---

*This glossary is the authoritative source for Indonesian translations. All translators must consult this document before translating any CIRIS content.*
