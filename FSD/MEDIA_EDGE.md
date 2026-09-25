# The Media Edge

What the client will record, share and render, decided from the parser outward, not the player inward.

**Origin:** the media decision brief of 2026-09-19 (five parallel surveys), until now kept only as a private artifact linked from CIRISServer#615. It is copied here so the list of supported types sits next to the code that enforces it. §1–§7 are the brief as written. §8 is this repo's state against it and is kept current.

**Normative source:** CIRISConstitution rc5 (@`44ae7b2`) **CC 5.3.2.5** (full-SHA before consumption), **CC 5.3.2.6** (render tier is receiver policy, computed from verified, sniffed bytes), **CC 3.3.13** (the Source struct). Where this document and the Constitution differ, the Constitution wins. The §3 table is CC 5.3.2.6's *recommended* set, not a normative one.

> **Media is the hardest edge because it is the only place a stranger's bytes get parsed by C.** Every KMP media library, including the ones the active federated clients ship, hands raw bytes to the platform decoder. That is the BLASTPASS shape.
>
> **The answer is a memory-safe Rust ingest core on the node, a receiver-decided Tier-A allowlist, and canonical re-encode before hashing.** Everything else (players, cameras, sharing) is a platform-API question the ecosystem has already settled.

## 1. The crux, and why it is solvable now

Five surveys ran in parallel: KMP media libraries, KMP media architecture, open-source phone agents, file-description standards with real product allowlists, and memory-safe media parsing in Rust. They converge on one picture.

The C decoder record is the argument:
- libwebp `CVE-2023-4863`: zero-click, CISA KEV, the same bug as Apple's BLASTPASS
- four libpng overflows, fixed Nov 2025
- five libheif advisories through 2026
- Skia `CVE-2023-2136`, exploited in the wild
- ImageIO `CVE-2025-43300`, exploited in the wild

iOS ImageIO runs **in the receiving app's process** for third parties, and Android decodes JPEG/PNG/WebP/GIF in-process via Skia. Neither platform isolates the common image formats for you.

What changed is that memory-safe decoders are now *production*, not aspiration:
- Chromium's default PNG decoder has been the Rust `png` crate since M139 (Aug 2025).
- JPEG is moving to `zune-jpeg`, and AVIF containers to CrabbyAvif.
- Signal ships Rust `mp4san`/`webpsan` (MIT, OSS-Fuzz) as a validator in front of every platform decoder.
- Android 17 moves AAC in-process as Rust.

The Rust-core survey grepped every candidate crate's source for `unsafe` and built a probe crate for Android arm64, iOS, Linux and wasm32: **the whole core is 4.7 MB per Android ABI**.

- **Images: solved in safe Rust.** `png`, `image-webp`, `gif`, `resvg`/`usvg` carry `#![forbid(unsafe_code)]`; `zune-jpeg` does with SIMD off. All are fuzzed via OSS-Fuzz.
- **Audio: solved in safe Rust.** Symphonia 0.6 (forbid unsafe, OSS-Fuzz 2026) handles MP3, AAC-LC, FLAC, Vorbis, WAV, and ALAC in MP4/OGG/MKV/WAV. A pure-Rust Opus exists but is six months old.
- **Video: validate in Rust, decode on hardware.** There is no production pure-Rust H.264/HEVC/VP9 decoder. The pattern: `mp4san` normalises, `h264-reader` validates SPS/PPS, then MediaCodec/VideoToolbox decodes. Signal and ChromeOS already do this.
- **PDF and 3D: never inline.** `hayro` is a safe PDF rasteriser but self-described as experimental, so first-page thumbnail only. glTF parses safely; USDZ/FBX have no safe parser. Download-only.

## 2. The standard to adopt

The constitution already fixes the shape:
- `external_content` has sub_kinds `image | audio | video | film | model_3d | live_stream`.
- Each sub_kind has a Source struct: dimensions, format, codec, duration, AI-generation disclosure, mandatory `alt_text`/`captions`, license.
- Bytes are SHA-256-addressed through `evidence_refs[]` and delivered by `ContentFetch` from a `holds_bytes:sha256:*` holder.
- CC 5.3.2.5 says the consumer **MUST verify the full SHA-256 before handing bytes to any renderer**.

So the only questions are what vocabulary fills the Source struct's `format`/`codec` slots, and what shape the descriptor takes.

**Adopt as-is:** IANA media types (RFC 6838) as the sole type vocabulary: lowercase essence, no parameters. Codec strings (RFC 6381 / AV1-ISOBMFF / RFC 7845) go in a separate `codecs` field. Every protocol surveyed (Matrix, AT Proto, Nostr, Signal, Mastodon, OCI) speaks this; there is no second candidate.

**Borrow the shape from:**
- the OCI descriptor (`mediaType` + `digest` as `sha256:hex` + `size`, with size verified *before* digest), merged with the AT Protocol blob ref. They are the same three fields, and a CIDv1 `raw`+sha-256 converts losslessly from our hex digest if we ever meet IPFS tooling.
- `width/height/duration` from Matrix `info`
- the placeholder from Signal/Mastodon/Nostr
- the filename rules from RFC 6266

**Do not adopt:**
- IPFS UnixFS: a chunked CID is *not* the sha256 of the bytes; it depends on DAG layout.
- C2PA: embedding a manifest changes the digest. Verify-only on the node later, if ever.
- ActivityStreams attachments: no hash, no size.

### The Source struct, concretely

```jsonc
{
  "digest":       "sha256:9f86d081…0a08",   // over the bytes the node stores & forwards; == evidence_refs[]
  "size":         1048576,                   // checked BEFORE hashing (OCI rule)
  "mediaType":    "video/mp4",               // bare IANA essence, RFC 6838-validated, string-equal for lookup
  "codecs":       "avc1.64001F, mp4a.40.2",  // required for video/mp4 + audio/mp4; the tier depends on it
  "width": 1280, "height": 720, "duration": 12340,   // layout hints only; re-derived from the header, never trusted for allocation
  "placeholder":  { "thumbhash": "1QcSHQRnh493V4dIh4eXh1h4kJUI" },  // ~25 bytes, decoded by arithmetic: zero parser risk
  "name":         "holiday.mp4",             // display only, RFC 6266 §4.3 sanitised
  "contentDigest":"sha256:…",                // optional: plaintext hash when digest is over ciphertext (Signal, NIP-17 ox)
  "derivedFrom":  "sha256:…"                 // optional: this is a rendition of that original
}
```

**What must not be in it:** any "safe" or "renderable" bit. The tier is a function the *receiver* computes from `mediaType`, `codecs`, `size`, dimensions, sniffed bytes and platform, against its own policy table, exactly as Element ignores `info.mimetype` for the blob-URL decision. The sender's `mediaType` is compared to magic bytes, never believed.

The constitution's Source struct list does not yet say three things. Each is borrowed from a shipped system:
- **`size` in bytes is required** (OCI's rule). It is what lets a receiver refuse a `ContentFetch` that overruns before hashing.
- **The mandatory captions ref is a sha256 of a separate `text/vtt` blob** with its own size cap, not inline text (AT Protocol's `captions[]`, ≤20 KB).
- **The AI-generation disclosure uses the IPTC Digital Source Type vocabulary** (`trainedAlgorithmicMedia`, `compositeWithTrainedAlgorithmicMedia`, `algorithmicallyEnhanced`, `digitalCapture`, …), which C2PA 2.1+ carries under the same URIs. So EU AI Act Art. 50 disclosure interoperates with C2PA and IPTC photo metadata without adopting C2PA's binary format.

All three have since landed in CC 3.3.13 (rc5).

The existing multimedia attestation families compose on top unchanged:
- `content_class:generated` / `generated_modified`, mandatory for AI-made content and mapped to the IPTC values above
- `content_rating:*` and `cw_class:*`
- the per-medium `image:*`/`audio:*`/`video:*` claim prefixes

Multicodec cannot fill the `format` slot: its 653-row table has no entry for jpeg, png, mp4, avc, aac or opus. It is a vocabulary for hashes, keys and IPLD codecs.

## 3. The sustainable set

This is the intersection of what Signal, WhatsApp, Mastodon, Threema and Element actually *emit*, what Android, iOS, Skiko and Chromium all decode, and what has a memory-safe decoder today. Delta Chat states the policy everyone else implements silently: *"it is a non-goal to support as many formats as possible in-app. Additional parsers come at security and maintenance costs."* Mastodon disabled HEIF/AVIF on 15 Sep 2026 "for security reasons".

| Media type | Tier | Constraints | Why |
|---|---|---|---|
| `image/jpeg` | **A · inline** | ≤16 MB, ≤33 Mpx | Universal; canonical output of Signal, Briar, Bluesky, Threema |
| `image/png` (APNG as PNG) | **A · inline** | ≤16 MB, ≤33 Mpx; reject bytes after `IEND` | Chromium's decoder is Rust |
| `image/webp` | **A · conditional** | ≤10 MB; `webpsan` on the node; re-encode on ingest | Bluesky CDN/Signal wire format, but CVE-2023-4863 was zero-click |
| `image/gif` | **A · inline** | ≤921,600 px, frame-count cap, ≤25 MB | Everyone renders it; `gif` crate forbids unsafe |
| `text/plain` | **A · inline** | UTF-8 only, ≤1 MB, bidi controls rendered visibly | No memory-unsafe parser |
| `video/mp4` | **A · conditional** | `codecs` required and must be `avc1.*` (≤L4.1) + `mp4a.40.2`; ≤100 MB, ≤3840×2160; `mp4san`; unlisted tracks rejected | H.264/AAC-LC is the one profile everyone emits; sandboxed on Android |
| `audio/mp4` (AAC-LC) | **A · inline** | ≤16 MB, duration cap | Signal voice notes, WhatsApp, Element |
| `audio/mpeg` (MP3) | **A · conditional** | ID3v2 ignored; never decode `APIC` | ID3 art re-enters the image decoders |
| `image/avif`, `image/heic`, `image/heif`, `image/jxl` | **B · convert at sender** | Sender converts to JPEG/PNG *before hashing*; raw arrivals are Tier C | Skiko cannot decode them on iOS/desktop; iOS HEVC is in-process with in-the-wild exploits |
| `video/webm`, `video/quicktime`, MKV | **B · convert at sender** | Transcode to the Tier-A MP4 profile before hashing | iOS has no WebM; MatroskaExtractor CVE-2026-28609 |
| `audio/ogg; codecs=opus`, FLAC, WAV, ADTS | **B · convert at sender** | Transcode to `audio/mp4` | AVFoundation will not play Ogg/Opus; one container, one demuxer |
| `image/svg+xml` | **B · rasterise on the node** | `resvg` with the file-href resolver disabled, size/depth caps, output PNG. **Never** a WebView, never the client | Static subset, no script engine, forbid unsafe (see §5). CC 5.3.2.6 ratified this as *C for the bytes, A for the node's PNG* |
| `application/pdf` | **C · download** | Node-only rasterisation to attested PNG pages in a seccomp-isolated process (Chromium's own PDFium arrangement), or a first-page thumbnail via `hayro`; never on a client | JS, Launch actions, embedded fonts; pdf.js CVE-2024-4367, PDFium 2024 ×2 |
| `model/gltf-binary`, `model/vnd.usdz+zip`, FBX, splats | **C · download** | Node may rasterise to an attested PNG poster in an isolated process; the model blob itself is never decoded on a client | 33 Apple USD CVEs through CVE-2026-20616 (Feb 2026); FBX SDK stack overflows CVE-2026-10709/10710 (Aug 2026); glTF: cgltf CVE-2026-32845, VTK CVE-2025-57108 (9.8), and Gitea CVE-2026-28737, *stored XSS through a glTF field rendered by a 3D viewer*. USDZ stacks three parser families (ZIP + USD + image). FBX and splats have no IANA type |
| HTML, Markdown-as-HTML, Office, fonts, archives, TIFF, PSD, BMP, ICO, everything else | **C · refuse** | Generic file card; dangerous-extension block on save | Excluded by name in Signal, Session, Threema, Element, Synapse, Delta, Mastodon |

### Streaming composes with the same schema

The constitution's `live_stream` (Phase 2: chunk-DAG, per-(stream_id, epoch) keys) maps directly onto **HLS over fragmented MP4 / CMAF (RFC 8216)**, which is what Bluesky serves:
- The init segment (`ftyp`+`moov`) is the per-epoch header: one sha256-addressed blob that every chunk references.
- Each chunk is one fMP4 segment (`moof`+`mdat`): independently hashable, ordered by sequence number.
- `codecs` lives on the stream once, never per chunk.
- `EXT-X-KEY`'s "applies to every segment until the next key" is exactly the epoch model, and `EXT-X-DISCONTINUITY` is the epoch boundary that changes encoding.

The property that matters: **init segment ‖ chunk₀ ‖ chunk₁ ‖ … is itself a valid `video/mp4` file**. So a recording is attested as an ordinary `video` blob with `derivedFrom: stream_id`: one schema for chunks and whole files. The stream manifest is an OCI-index-shaped list of chunk descriptors, itself sha256-addressed, so `evidence_refs[]` cites it with no DAG codec.

SHA-256 has no tree mode, so verified streaming is a manifest of chunk hashes (HLS, OCI and UnixFS all do this). BLAKE3/Bao would allow a single tree hash, but the constitution fixes SHA-256.

## 4. The pipeline (node-side, in Rust)

CIRIS already ships a Rust substrate into every platform: `ciris_server` via PyO3 on Android and iOS, and native binaries on desktop. A media core is the *same* build problem, already solved. Putting ingest on the node means every client, not just this one, only ever renders bytes the node's safe encoder produced.

1. Receive into quarantine; check `size`; stream-hash and compare `digest` to `evidence_refs[]`. Refuse on mismatch before any parse.
2. Sniff the first 2 KB with a masked-prefix table (WHATWG rows + `ftyp` brand walk + `OggS`). The sniffed essence must **equal** the declared `mediaType`. Run anti-polyglot trailer checks: bytes after `IEND`/`FFD9`, and a ZIP EOCD in the last 64 KB. Do not link libmagic; it has its own CVEs.
3. Enforce caps from the header before decoding: pixels, frames, duration, track count, SVG depth.
4. Decode in the Rust core (images, SVG, audio) under a time budget. Video: `mp4san` normalises → `h264-reader` checks SPS/PPS/level → allow only `avc1`/`mp4a` tracks → strip every other box.
5. Re-encode canonically:
   - PNG for lossless, JPEG q85 for photographic
   - GIF or lossless WebP for animation
   - SVG → PNG at display size
   - audio → PCM → AAC via the platform encoder (its input is our PCM)

   Never emit the original's bytes for rendering.
6. Strip everything that is not pixels or samples: EXIF, XMP, ICC (Little-CMS CVE-2018-16435 is why Chromium moved ICC to Rust `moxcms`), GPS, and `udta`/`meta`/`uuid` boxes.
7. Emit the rendition as a *separate* blob with its own digest, and a descriptor whose `derivedFrom` points at the original. The original stays hash-stable and marked "never decode outside ingest".

**Sender side, in our own client:** canonicalise before hashing. Every image goes to JPEG/PNG/WebP, and every video to H.264/AAC-LC MP4, via the platform encoder. Federation peers running this client then mostly receive already-canonical files, and EXIF/XMP/ID3 never leave the device. This is what Signal, Bluesky's app, Threema, Briar, SimpleX and Delta Chat all do.

### Core composition

| Concern | Crate | Unsafe policy (verified from source) |
|---|---|---|
| PNG/APNG | `png` 0.18 | forbid; OSS-Fuzz; Chromium default |
| JPEG | `zune-jpeg` + `jpeg-encoder`, SIMD off | forbid with SIMD features off; Chromium's choice |
| WebP | `image-webp` decode; `webpsan` gate | forbid; Signal-proven |
| GIF | `gif` | forbid |
| SVG | `resvg`/`usvg`, href resolver → `None`, no system fonts | forbid (tiny-skia SIMD-only unsafe) |
| Audio | `symphonia` 0.6 (wav pcm flac vorbis ogg mp3 aac isomp4 mkv) | forbid; OSS-Fuzz 2026 |
| MP4 normalise / H.264 validate | `mp4san`, `h264-reader` | 0 unsafe / forbid; mp4san on OSS-Fuzz |
| Hashing | `sha2` | — |
| Governance | `cargo-deny` (allow MIT/Apache/BSD/Zlib/MPL; deny AGPL), `cargo-audit`, forbid-check on every decode-path dep, `cargo fuzz` seeded from the image-rs/symphonia/mp4san corpora, enrol in OSS-Fuzz | What Chromium and Signal do |

Keep out:
- anything with a C dependency (dav1d, libwebp, mozjpeg, libopus, pdfium)
- the AGPL imazen crates (`heic`, `zenwebp`)
- `tiff` and `exr`
- `jxl-oxide`, until a browser ships it under continuous fuzzing

Measured: image + symphonia + resvg + opus + mp4san = **4.74 MB** arm64 `.so`, **4.41 MB** wasm32. Dropping SVG text rendering saves 1.5 MB.

### Delivery into the client

**Gobley** (UniFFI for Kotlin Multiplatform, MPL-2.0) generates one binding for Android (JNA), JVM and Kotlin/Native iOS (cinterop). This is the Element X / Mozilla / rust-nostr pattern. There is no wasm path. On web, either use a `wasm-bindgen` facade of the same crate, or rely on the browser's own sandboxed decoders (`createImageBitmap`); the browser is the one platform where the platform decoder *is* isolated.

If ingest lives on the node, the client needs the core only for sender-side canonicalisation and thumbhash, a much smaller surface.

## 5. Where the surveys disagree, and the call

**SVG.** The standards survey says refuse: every messenger excludes it by name, because it is XSS/XXE as active content. The Rust survey says `resvg` renders a static subset with no script engine and forbids unsafe. The only real hazard it found is the default file-href resolver reading local paths (fix: override it). Both are right about their own layer.
**Call:** SVG never reaches a client renderer or a WebView. The node may rasterise it to PNG with hardened `resvg` options as a Tier-B conversion. If that feels like one parser too many, drop it to C; nothing in the product needs SVG from strangers.

**Opus.** It is the best codec, and Android 17 isolates it under LFI. But AVFoundation will not play Ogg/Opus, and the pure-Rust decoder is one author, six months old.
**Call:** Tier B (transcode to AAC-LC at the sender) until `opus-decoder` has a differential test against libopus in CI. Revisit in two releases.

**AVIF.** The container parses safely (CrabbyAvif, `mp4parse`). AV1 decode is `rav1d` (Rust + dav1d assembly, ~5% slower) or C.
**Call:** Tier B now; promote when rav1d's remaining unsafe is acceptable.

## 6. The libraries and phone agents, in that light

With the parser question settled, the player/camera/share question is a platform-API question, and the field has converged:

- **Playback: kdroidFilter/ComposeMediaPlayer** (MIT, 0.11.4). The only honest four-platform player: Media3 / AVPlayer / per-OS JNI on desktop / HTML5. Flare, Pixelix and Fread use it. The Chaintech player is closed-source under an Apache label. ComposeMediaPlayer plays whatever it is given, so it only ever gets renditions.
- **Camera: Kamera** (Apache-2.0, 1.2) is the only option with real desktop video recording (JavaCV). **Camposer** (Apache-2.0) covers mobile only. peekaboo is abandoned; compose-camera is DMCA-blocked.
- **Audio record: Kodio** (Apache-2.0) covers all four targets for real. hyochan's library claims desktop/wasm and returns "not implemented" on both.
- **Screen record:** no KMP library. Wrap MediaProjection (Android), ReplayKit (iOS in-app; the Broadcast extension must stay Swift), and ScreenCaptureKit / PipeWire / DXGI on desktop. `java.awt.Robot` is black on Wayland.
- **Files & share: FileKit** (MIT, 0.16) for pickers on all four platforms. Share sheets exist only on Android/iOS (FileKit or software-mansion/kmp-sharing); desktop and web need an explicit fallback. The picker at the time was base64-in-memory with a 10 MB cap, no video/audio types, and wasm stubbed. It has to become streaming.
- **Live streaming: webrtc-kmp** (Apache-2.0) covers Android/iOS/web, but has no JVM target and twelve months without a commit. LiveKit closed its KMP request as "not planned". This matches the constitution's `live_stream` being Phase 2.

### Phone agents: what is actually liftable

Two facts reframe this category:
- **Google Play now prohibits** any AccessibilityService use that "enables an app to autonomously initiate, plan, and execute actions". Every on-device Android agent that drives other apps is sideloaded, and OpenClaw compiles that code only into a non-Play flavour.
- Almost none of the famous research agents (AppAgent, Mobile-Agent, UI-TARS, DroidRun) run on the phone at all. They drive it from a PC over ADB.

The installable, permissively licensed set is small, and it is where the reusable capture code lives:

| Source (license) | Lift | Size |
|---|---|---|
| OpenClaw apps/android + apps/ios (MIT), shipped on Play/App Store | `CameraCaptureManager.kt` (CameraX jpg+mp4 clip), `AndroidAudioInputSession.kt`, `VoiceWakeManager.kt`; iOS `ScreenRecordService.swift` (ReplayKit → AVAssetWriter), `CameraController.swift`; their `node.invoke` command schema (`camera.snap`, `camera.clip`, `screen.record`) as a `commonMain` contract | 460 / 500 / 620 / 593 / 683 lines |
| Home Assistant Android + iOS (Apache-2.0) | `VoiceAudioRecorder.kt` (16 kHz PCM16 shared Flow, 10 ms chunks), binary-frame WS framing, `microwakeword`; iOS `AudioRecorder.swift`, `SpeechTranscriber.swift` | 235 / 233 / 177 / 401 |
| Xiaomi-GUI-0 guiness companion (Apache-2.0) | `ScreenCaptureManager.kt` (MediaProjection → ImageReader → JPEG, latest-frame cache, FGS types), Ktor on-device server with bearer auth + QR pairing | 199 / ~500 |
| 4AIs/openclaw-android (MIT) | The whole `accessibility/` package: tree builder, set-of-mark ids, occlusion, post-action diff, action dispatcher. This is the privacy-preserving *text* observation route. Sideload only. | 2,189 |
| LiveKit SDKs (Apache-2.0), sherpa-onnx (Apache-2.0) | As dependencies: MediaProjection-as-WebRTC-capturer, ReplayKit Broadcast extension + IPC; one C API for STT/VAD/KWS/TTS on Android and iOS | — |

Study but do not copy: mobilerun-portal (AGPL) for its transport matrix and IME-typing trick; Operit (LGPL); Signal's libsignal (AGPL; its `mp4san`/`webpsan` are MIT and fine to use).
Skip: the research-agent line, Open Interpreter 01 (AGPL, dormant), Screenpipe and Cactus (not open source), Blurr (non-commercial).

The iOS reality:
- There is no accessibility tree of other apps, and no way to ship an "agent that drives apps" (XCUITest only runs from Xcode).
- What an App Store app *can* do is App Intents/Shortcuts, ReplayKit with per-session consent, CallKit for VoIP it carries itself, and unrestricted mic/camera/TTS/files.
- Cellular call audio is off-limits on both platforms. An AI that talks on calls is a server-side SIP agent, not a phone app.

## 7. What to do first

1. **Fix the descriptor into CEG.** Fill the `external_content` Source struct's format slots with the shape in §2: IANA essence + `codecs` + OCI-form digest/size + thumbhash + `derivedFrom`. No "safe" bit. This is the server/UI alignment point, and it is small.
2. **Stand up the Rust media core on the node** with the §4 composition: `#![forbid(unsafe_code)]` at the crate root, cargo-deny/audit/fuzz in CI. Start with images and audio; video is validate-then-platform from day one.
3. **Ship the Tier-A allowlist as the receiver's policy table in the client**, computed from sniffed bytes, never from the descriptor. Show the thumbhash first; decode only after every gate passes.
4. **Replace the picker.** Stream bytes (Okio/kotlinx-io) instead of holding base64 in memory; use FileKit for dialogs; canonicalise on the sender (JPEG/PNG/WebP, H.264/AAC-LC MP4) *before* hashing.
5. **Then** playback via ComposeMediaPlayer and capture via Kamera/Kodio and the OpenClaw/Home Assistant lifts, all fed renditions only. Live streaming waits for the constitution's Phase 2, and for a KMP WebRTC layer that has a desktop target.

> **The one rule that survives every layer:** a client renders only bytes that a memory-safe encoder we control produced, from a blob whose full SHA-256 it verified, of a type its own policy table admits. The sender's claims are inputs to a comparison, never permissions.

## 8. State in this repo (kept current)

As of 2026-09-25 (CIRISClient#77):

| brief | here | waiting on |
|---|---|---|
| §7.1 descriptor in CEG | done upstream: CC 3.3.13 (rc5), persist v45 `media` member | the drive routes returning it: **CIRISServer#641** |
| §7.2 node media core, renditions | not started | **CIRISServer#614** |
| §7.3 receiver policy table | **done**: `models/drive/RenderTier.kt`. Sniffs 2 KB (masked prefix + `ftyp` brand), requires sniffed == declared, refuses polyglots (ZIP EOCD in the last 64 KB, bytes after `IEND`/`FFD9`), renders `text/plain` only as valid UTF-8 with bidi controls shown visibly, refuses HTML/SVG/archives/executables, and blocks saving a copy of a mismatch, a polyglot, or anything that runs code. The table is `MediaPolicy.RECOMMENDED` (the §3 caps) | the node's own table: **CIRISServer#643** (`GET /v1/media/policy`), CIRISEdge#638 item 5 |
| CC 5.3.2.5 full-SHA before render | **not done**: nothing to verify against | `content_digest` / `size` on the routes: **CIRISServer#641** |
| thumbhash first | not done | `placeholder` on the routes: **CIRISServer#641** |
| sniff at the node's write door | the client sniffs on receive regardless | **CIRISServer#642** (RFC 6838, sniff, RFC 6266 on `POST /v1/files`); CIRISEdge#638 item 1 at the adopt door |
| §7.4 streaming picker, sender canonicalisation | not done: upload is inline base64, capped at 1 MiB (the node's inline cap) | streaming create: CIRISServer#615 §1; large files over the chunk DAG: CIRISEdge#633 |
| §7.5 playback, capture | not started | renditions (#614) |
| family files | the tab says why it is empty | **CIRISServer#627** |
| cross-node bytes | "on another device" is shown honestly | CIRISPersist#870, CIRISServer#604 |

**One interim call.** CIRISServer#615 ("Requested", item 3) proposed that, before renditions exist, the client render Tier A originals it verified and sniffed itself. This repo follows §7's closing rule instead: **the client does not decode a stranger's image, audio or video bytes at all until the node produces a rendition.** Until then those files say they are waiting for the node, and a copy can still be saved. Two things support the stricter reading. The client also cannot verify the full SHA today (#641), so "verified" isn't available. And every in-process decoder on the client is exactly the C surface §1 is about.

A wire wart the client works around: `/v1/notes` says `open` where `/v1/drive` says `here` (**CIRISServer#644**).
