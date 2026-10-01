# The Media Edge

What the client will record, share and render, decided from the parser outward, not the player inward.

**Origin:** the media decision brief of 2026-09-19 (five parallel surveys), until now kept only as a private artifact linked from CIRISServer#615. It is copied here so the list of supported types sits next to the code that enforces it. §1–§7 are the brief as written. §8 is this repo's state against it and is kept current. §9 is the contract for agent consumers (CIRISAgent).

**Last verified (2026-10-01):** CIRISServer `v0.5.218` (commit `405acc17`; tagged, not yet released), substrate edge v38.1.0 / persist v52.0.1; CIRISConstitution rc5 @`44ae7b2` (plus CC 3.1.3.3 from the `rc6` branch @`798ebf79`, cited as such); vendored registry `client/ceg/namespace_registry.json` sha256 `90b30c61e71fd158291acdac60468f88e4899a22ccc2471243be93670ad01ad3` (`source_sha256` `95665a2c…`); CIRISAgent `main` @`6f002885` (2.12.1). §8 and §9 are written against these; §1–§7 are the brief as written.

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

As of 2026-10-01, against CIRISServer **`v0.5.218`** (tag object `2f8427e0`, commit `405acc17`; two test-only commits after `53d1ffb5`). **0.5.218 is tagged but not released**: there is no GitHub release for it and PyPI's newest `ciris-server` is 0.5.217. Every server citation below is `file:line` at that tag. The substrate it pins is edge v38.1.0 / persist v52.0.1 (`Cargo.toml:138-139`).

| brief | here | waiting on |
|---|---|---|
| §7.1 descriptor in CEG | **Upstream:** CC 3.3.13 (rc5) fixes the Source struct, and persist v45 carries it as the envelope's `media` member (CIRISServer#614, comment of 2026-09-20). Since CIRISPersist#922 (closed) that gate also admits a *sealed* descriptor. **On the wire at 0.5.218:** `size` and a plaintext `content_digest` (`sha-256`) come on the JSON open (`src/drive.rs:2843-2853`) and on `/meta` (`:2467-2504`). A whole raw read at or under 64 MiB also sends RFC 9530 `Repr-Digest`. `at_rest_sha256` is the sealed blob's hash and never the plaintext's (`:2505-2509`). The **listing carries no digest**: a `GET /v1/drive` row has `size` (only while the bytes are here), `description` and `custody` (`DriveEntry`, `:245-286`). New in 0.5.218 (CIRISEdge#698): a file's name and type are sealed with its bytes, so a row reads `description: clear \| opened \| sealed`, and under `sealed` the `filename`/`media_type` are *unknown*, not absent. **Not built:** the digest is the node's statement about bytes it decrypted. No author signed it. | A digest **signed into the row**: CIRISEdge#638 (open). `derived_from`, `placeholder` and dimensions: CIRISServer#641 (open, "partly addressed in v0.5.217") and #614 |
| §7.2 node media core, renditions | Not started. The node says so itself: `"renditions": false` (`src/media_gate.rs:337`) | **CIRISServer#614** (open; nothing on it since 2026-09-25) |
| §7.3 receiver policy table | **Done:** `models/drive/RenderTier.kt`. It sniffs 2 KB (masked prefix plus `ftyp` brand) and requires sniffed == declared. It refuses polyglots: a ZIP EOCD in the last 64 KB, or bytes after `IEND`/`FFD9`. `text/plain` renders only as valid UTF-8, with bidi controls shown visibly. HTML, SVG, archives and executables are refused. Saving a copy is blocked for a mismatch, a polyglot, or anything that runs code. Downstream, the client declares the SNIFFED type, never the label. The table is the node's `GET /v1/media/policy` (0.5.217+, public, `src/drive.rs:3913-3915`; body `src/media_gate.rs:306-338`) narrowed by `MediaPolicy.RECOMMENDED` (the §3 caps), and never widened. The built-in table stands only where the node has no route, and the sheet says so. **The node's write gate is narrower than this table** (0.5.217, CIRISServer#642 closed). It checks an RFC 6838 essence and a sniff of the first 64 KiB, plus UTF-8 with no NUL for `text/*` (`src/media_gate.rs:189-230`). It runs no polyglot check, no size or pixel cap and no tier refusal: any honestly labelled type, `text/html` included, is stored. So the receiver's table is still the boundary. | An operator narrowing at the node: CIRISEdge#638 item 5 (open) |
| CC 5.3.2.5 full-SHA before render | **Done** for files the JSON read can return (≤ 64 MiB). `DigestCheck.of` (`models/drive/Drive.kt:127`) hashes the opened bytes (`platform/util/Sha256.kt`) against the node's `content_digest`. A mismatch is **unreadable**, neither shown nor saved. When no digest comes back, the sheet says "not verified". **Not done:** the separate size-before-hash comparison. The JSON read is a single value, so `size` and the bytes come from the same response. Above 64 MiB the node has no plaintext digest to give: `?raw=1` streams without `Repr-Digest` (`src/drive.rs:2795-2805`), and the client does not read that path (next row). | The digest signed into the row: **CIRISEdge#638** |
| thumbhash first | Not done | `placeholder` on the routes: **CIRISServer#641** (open) + #614 |
| sniff at the node's write door | **Upstream done** (0.5.217, CIRISServer#642 closed): `drive.bad_media_type` 400, `drive.format_mismatch` 415 `{declared, sniffed}`, `drive.bad_filename` (`src/drive.rs:1069-1110`). Since 0.5.218 the streamed upload peeks the same 64 KiB window, so it runs the same gate (`src/media_gate.rs:184-189`). The client still sniffs on receive regardless | CIRISEdge#638 item 1, at the adopt door (open) |
| §7.4 large files, streaming picker, sender canonicalisation | **The node (0.5.218, CIRISServer#702):** `POST /v1/files` takes JSON (`bytes_base64`) or `multipart/form-data`. JSON, or multipart without a `size` field, is held whole up to **64 MiB** (`WHOLE_READ_CAP`, `src/drive.rs:83`), and above that it answers `413 drive.too_large`. Multipart **with** `size`, where every field comes before the `file` part, streams into edge's `files::publish_stream` up to **2.5 GiB** (`STREAMED_FILE_CEILING`, `:109`). Its refusals are `drive.field_after_file`, `drive.declared_length_mismatch` and `413 drive.too_large` (`:874-997`). Edge stores anything over its **1 MiB inline bound** as a chunk DAG (`inline_max_bytes`, `src/media_gate.rs:335`). Reads: the JSON read is whole and **refuses above 64 MiB with `413 drive.too_large_for_whole_read`** (`src/drive.rs:1869-1879`). `?raw=1` streams any size from `FileRow::chunks()`, and `Range` is served in 1 MiB windows (`:2786-2810`). **Known limits, from the release thread (CIRISServer#697, 2026-10-01):** a 256 MiB self file round-trips byte-identical, but it is slow, because every chunk gets its own DEK wrapped to every recipient ("files > 64 MiB are grant-bound"). The fix is CIRISPersist#969 (open, persist v53, design only). Directories come in the next cut (CIRISPersist#962, open). **The client:** it uploads JSON base64 only (`CIRISApiClient.kt:1714-1739`). The view model's cap is the node's `whole_read_max_bytes` (`MediaPolicy.uploadCap`), but every platform picker drops anything over **10 MB** first (`PickedFile.MAX_FILE_SIZE_BYTES`, `platform/FilePicker.kt:19`, enforced in the iOS/desktop/Android pickers) and holds it in memory as base64. It reads with the JSON form only, so a file over 64 MiB cannot be opened here. Naming that state (`drive.too_large_for_whole_read` → "too large to open on this device yet") is **in review, CIRISClient#143 (open)**. No streaming read is planned in it. There is no sender canonicalisation | A streaming picker plus a multipart-with-`size` upload, and a `?raw=1` reader: not filed here. Faster large files: CIRISPersist#969. Directories: CIRISPersist#962. The chunk-DAG door itself shipped in edge v36 (CIRISEdge#744/#737, closed), but **CIRISEdge#633**, the attachments issue, is still open |
| §7.5 playback, capture | Not started | Renditions (#614) |
| family files | **Done:** Family › Files lists the room of the household picked in the Family hub (CSD-100). Three zeroes are kept apart: no household, no files, households unreadable. CIRISPersist#910 (closed in persist v49) made family roster changes replicate, and 0.5.218 carries persist v52, in which "a family's files cross to the members' devices" (`Cargo.toml:3`, SUBSTRATE). **Open defect, found by code reading and not run:** the client opens a file with `room_id` only (`CIRISApiClient.kt:1661`). The node defaults a missing `cohort` to `self` (`src/drive.rs:146-148`), and the `self` room ignores `room_id` (`:427-429`). So opening a family or community file should answer `drive.not_in_room`. CSD-107's custody call does send `cohort` (`CIRISApiClient.kt:1689-1695`) | A client fix: send `cohort` on `GET /v1/files/{id}` |
| cross-node bytes | "On another device" is shown honestly (`not_fetched`, 409). CIRISPersist#870 and CIRISServer#604 are both closed. The 0.5.218 three-peer ladder reports "corpus 25/25 byte-identical" on a second device (CIRISServer#697, 2026-10-01). History from before a device joined opens only for self files until CIRISPersist#916 (`Cargo.toml:3`) | Pre-join family history: CIRISPersist#916 |
| **file custody** (CSD-107) | **Card built** (CIRISClient#139, merged): `FileCustodySheet.kt`, `FileCustodyViewModel.kt` and `models/drive/Custody.kt`, opened from every file surface's row. The route is `GET /v1/files/{id}/custody` (0.5.218, CIRISServer#704 merged, `src/drive.rs:2526-2548`), and every drive row carries `custody: {devices_total, received_on}`. **What it does not say,** per CIRISServer `FSD/FILE_CUSTODY.md`: a receipt proves *delivery*, not holding, and an eviction does not retract one (§4 gap 3). Self and family copies cannot be counted, by design (`copies_observable: false`, CC 5.2). Only this device can say "none" (`reported_at` is always `null`) until CC 3.1.3.3 `custody:ack:v1` reports land (CIRISConstitution#130, open; persist v52 part 2). Copy-to and remove-from a device are designed and not built (§5). **Corrected since the first 0.5.218 cut:** inline (≤ 1 MiB) files *are* receipted now. `receipts_supported` is always `true`, and `custody.inline_no_receipt` / `custody.receipt_time_unknown` are no longer emitted (`FSD/FILE_CUSTODY.md` §4.1; `src/file_custody.rs:80-83`). The client still parses both for older nodes. **Stale in code:** `readFileCustody`'s KDoc (`CIRISApiClient.kt:1683-1687`) and CSD-107's stage note still call the route "on no released node". It is tagged now but not released | Custody acks: CIRISConstitution#130. Receipt retraction on eviction: FILE_CUSTODY §4 gap 3. CSD-107's flow floor moves off `unreleased` when 0.5.218 is published |
| **413 too-large read** | **In review**: CIRISClient#143 (open) maps `drive.too_large_for_whole_read` to `OpenState.TooLarge`, which says "This file is too large to open on this device yet". It adds a CSD-007 row for it. Main does not have it yet | The PR. A real fix is a `?raw=1` streaming reader (§7.4 row) |

**One interim call.** CIRISServer#615 ("Requested", item 3) proposed that, until renditions exist, the client render Tier A originals that it had verified and sniffed itself. This repo follows §7's closing rule instead: **the client does not decode a stranger's image, audio or video bytes at all until the node produces a rendition.** Until then those files say they are waiting for the node, and a copy can still be saved. Two things support the stricter reading. First, the full SHA is verified now (0.5.217), but a verified original is still a stranger's bytes in an in-process decoder, which is exactly the C surface §1 is about. Second, the node says `renditions: false` in its own policy, so the sheet can say "this node can't do that yet" instead of waiting.

A wire wart the client still maps for older nodes: 0.5.215/216 `/v1/notes` said `open` where `/v1/drive` says `here`. 0.5.217 made it one word per fact (`drive::BYTE_STATES`, `src/drive.rs:233-242`, **CIRISServer#644** closed).

## 9. For agent consumers: CEG-native blobs

Written for the CIRISAgent team, who are adding blob support to the agent's reasoning pipeline and tools. Read it as a contract, scoped to what is verified: every line cites the constitution, the server at `v0.5.218` (`405acc17`), or this repo. **Where this section and the Constitution differ, the Constitution wins.** Where it and the server differ, the server's code at the cited line is the fact, and this section is wrong.

### 9.1 What a blob is, in CEG terms

- **The bytes are addressed by SHA-256.** A row cites them from `evidence_refs[]` as a bare sha256, and the CC 3.3.13 Source struct's `digest` is "over the bytes as stored and forwarded; == evidence_refs[]" (CC 3.3.13, rc5 `part_3_the_namespace.md:1453-1505`). When the stored bytes are ciphertext, `content_digest` is the optional plaintext hash (same block).
- **A drive file is a row plus sealed bytes.** "A file is bytes sealed at a room's tier plus a row citing them" (`src/drive.rs:6-8`). The row's **attestation id** is the handle every route takes. The node hands back two hashes, and they are not the same thing:
  - `at_rest_sha256` is the hash of the sealed blob or chunk manifest. It is "NOT a digest of the file's bytes; a client must not verify plaintext against it" (`src/drive.rs:2505-2507`).
  - `content_digest` is the plaintext SHA-256, which the node computes over the bytes it just decrypted (`:206-216`, `:2851`). It is the node's statement, and **no author signed it** (CIRISEdge#638, open).
- **Holder discovery is `holds_bytes:sha256:{prefix}`** (CC 3.1.9.1; vendored registry row `holds_bytes:sha256:{prefix}`, `client/ceg/namespace_registry.json`). It carries only a short prefix, and a consumer "MUST NOT short-circuit verification to the prefix" (CC 5.3.2.5). For `self` and `family` the substrate records **no** holder claims: those bytes are never announced (CC 5.2). `/meta` says so as `holder_claims_recorded: false` with `devices_holding: null`, not as `0` (`src/drive.rs:2476-2486`, `:2518-2519`).
- **The media descriptor** (CC 3.3.13) is `digest`, `size` (REQUIRED, checked before hashing), `format` (IANA essence), `codec` (REQUIRED for `video/mp4`/`audio/mp4`), layout hints, `placeholder`, `name` (display only), `content_digest` and `derived_from`. It "MUST NOT carry … any `safe` / `renderable` / tier bit." Renditions are **separate blobs** with `derived_from`, and the node stores and forwards verbatim. **None of `derived_from`, `placeholder`, `codec` or dimensions is on any drive route today** (CIRISServer#641, open).
- **Scope is the room.** A file lives in exactly one room: `self` (the owner's devices), `family` (needs `room_id` = the family id) or `community` (needs `room_id`). An unknown cohort is `400 drive.unknown_cohort` (`src/drive.rs:131-156`, `:427-462`). A file is changed by publishing a new row and withdrawing the old one. Delete is a `withdraws`, and the bytes then read `410 drive.withdrawn` (`:24-31`).
- **Registry families you may cite** (vendored rc5 registry, sha256 `90b30c61…`): `holds_bytes:sha256:{prefix}`, `content_class:{class}`, `content_rating:{scheme}:{rating}`, `cw_class:{class}`, and `delivery_receipt:{stream_id}`, which is *reserved*. The per-medium `image:*` / `audio:*` / `video:*` prefixes that §2 of the brief mentions are **not** in this registry. Neither is `custody:*`, which is on the CIRISConstitution `rc6` branch (CC 3.1.3.3, `798ebf79`) and not in rc5. Do not emit any of those three.

### 9.2 Custody and receipts, and their honest limits

- **Delivery receipts** (`delivery_receipt:{stream_id}`, CC 5.3.3.6) are a subscriber's signed acknowledgement that it received chunk K of a stream at an epoch. On the drive, edge emits them per file (CIRISEdge#738, closed), and `GET /v1/files/{id}/custody` reports them (CIRISServer `FSD/FILE_CUSTODY.md` §1.1, §2.3).
- **A receipt proves delivery, not holding.** Evicting a copy does not retract its receipt (`custody.receipt_is_delivery_not_holding`; FILE_CUSTODY §4 gap 3).
- **Inline files are receipted** since 0.5.218's edge v38 / persist v52 (FILE_CUSTODY §4.1; CIRISPersist#953 closed). On a node older than 0.5.218 there is no custody route at all.
- **Copies of self and family files cannot be observed, by design.** `copies_observable: false` follows CC 5.2: those bytes are never announced. A receipt is the only sign that one of the person's devices got the file (`custody.copies_unobservable_by_design`).
- **`holds` is one of `here | received | none | unknown`.** Only *this* device can say `none` today, and `unknown` is **not** "absent" (FILE_CUSTODY §1.1). The per-device `custody:ack:v1` report that would let other devices say `none` is CC 3.1.3.3 on `rc6` (CIRISConstitution#130, open). That clause is also where "a UI MUST NOT present *unknown* as *none*" is written.
- Receipts are admitted on the device that **wrote** the file. Any other device lists only the receipts it holds (`custody.receipts_admitted_on_author_device`).

### 9.3 The routes

All drive routes are the **node's** (`src/drive.rs:3911-3929`). Authentication is the same everywhere except the policy route: a bearer that resolves to **the node owner's own session** (`SystemAdmin` + `FullAccess`) on a node that has an owner binding. **A delegated (`dgrant:`) session is refused**, on reads as well as writes (`src/drive_auth.rs:35-56, 73-114`). Without a session the answer is `403 drive.owner_session_required`. A delegate that tries to write gets `403 drive.delegate_may_not_author`. Every refusal is `{error, reason_id, detail}` (`src/drive.rs:336-362`).

| route | since | essentials |
|---|---|---|
| `GET /v1/media/policy` | 0.5.217 | **Public** (no session). Answers `tier_a` caps, the tier B/C lists, `write_gate`, `inline_max_bytes` = 1 MiB, `whole_read_max_bytes` = 64 MiB and `renditions: false` (`src/media_gate.rs:306-338`). Narrow by it; never widen. |
| `GET /v1/drive?cohort=&room_id=&limit=&after=&include_withdrawn=` | 0.5.215 | Pages of ≤ 500 rows (`MAX_PAGE`, `:119`), with `resume` as the next `after`. Each row has `attestation_id`, `cohort`, `room_id`, `filename`, `media_type`, `description`, a `bytes` state, `size` (only when `here`), `envelope` and `custody`. It has **no digest** (`:245-286`). |
| `GET /v1/files/{id}?cohort=&room_id=` | 0.5.215 (digest 0.5.217) | JSON: `media_type`, `filename`, `size`, `content_digest`, `content_digest_alg`, `bytes_base64` (`:2843-2853`). **Send `cohort`**: it defaults to `self`. Over 64 MiB it answers `413 drive.too_large_for_whole_read`. |
| `GET /v1/files/{id}?raw=1` | 0.5.216 (streaming 0.5.218) | The bytes, with `Content-Type`, `Content-Disposition` and `Range`. At or under 64 MiB it also sends `Repr-Digest`. Above 64 MiB it streams with **no** digest of any kind. A `Range` gets a 206 with no digest (`:2786-2810`). |
| `GET /v1/files/{id}/meta` | 0.5.216 (digest 0.5.217) | Everything except the bytes, including `content_digest` (only when the bytes are here and ≤ 64 MiB), `at_rest_sha256`, `chunked`, `tier`, `withdrawn` and `holder_claims_recorded` (`:2422-2524`). |
| `GET /v1/files/{id}/custody` | **0.5.218, unreleased** | See §9.2. It goes through the same doors as the bytes, and a viewer who cannot open them gets the byte read's refusal (`:2526-2546`). |
| `POST /v1/files` (and `PUT /v1/files/{id}`) | 0.5.215 (multipart 0.5.218) | JSON `{cohort, room_id, bytes_base64, media_type, filename}`, ≤ 64 MiB, or multipart with `size` before `file`, ≤ 2.5 GiB (§8, §7.4 row). Refusals: `drive.bad_media_type`, `drive.format_mismatch` (415), `drive.bad_filename`, `drive.too_large` (413), `drive.field_after_file`, `drive.declared_length_mismatch`. The answer is `{attestation_id, crossed, addressed, excluded, …}`, and `crossed: false` "means the file reached nobody" (`:178-212`). Read back by the **returned** id: after a widening it is a new row (`:464-485`). |

**Byte states and their codes** (`src/drive.rs:233-242`, `:1845-1859`): `here` (200), `not_fetched` (409: the bytes are on another device, not here yet), `not_granted` (403), `withdrawn` (410), `evicted` (410), `seal_mismatch`, `unopened` (500).

**Node URL or the agent's port.** Since agent 2.12.1 (CIRISAgent#1213 closed, PR #1215), `routes/node_proxy.py` forwards every `/v1` path the brain does not serve to the folded node on `127.0.0.1:4243`. Unknown paths stay 404 when the node is down (`node_proxy.py:1-50`). Through that proxy:
- The request body is read **whole** (`await request.body()`), and so is the response (`upstream.content`), under a **30 s** timeout (`node_proxy.py:69, 73, 222, 255`). Streaming does not survive the hop, so large uploads and `?raw=1` reads belong on the node URL.
- Only `GET/POST/PUT/PATCH/DELETE` are proxied (`node_proxy.py:203`).
- The agent's `requirements.txt` at `main` (`6f002885`) pins **`ciris-server==0.5.217`**. An agent install therefore has no custody route, no multipart upload and no streamed read above 64 MiB until it adopts 0.5.218.

### 9.4 Rules an agent MUST follow before blob content reaches reasoning or a tool

Each rule names the section that makes it a rule. "MUST" here is the Constitution's word only where a CC section is cited. The rest is this document's recommended policy, CC 5.3.2.6's "recommended set", and it is marked as such.

1. **Full SHA-256 before any decode, parse or model call** (CC 5.3.2.5): "verify the full SHA-256 of received bytes … BEFORE handing the bytes to any consumer (Agent loader, …)". Verify against `content_digest` from the same JSON read or from `/meta`, never against `at_rest_sha256`. On a mismatch, discard the bytes. Above 64 MiB there is no plaintext digest on any route (§9.3). Until CIRISEdge#638, such a file **cannot be verified**, so it must not be consumed.
2. **Size before hash** (CC 5.3.2.5): compare the received length to the declared `size` first. A mismatch is a refusal "without reading the rest".
3. **Tier from sniffed bytes; a mismatch is a refusal, not a correction** (CC 5.3.2.6). Sniff the leading bytes and require sniffed == declared. Do not trust `media_type`, the filename or its extension. The node's write gate is **not** a substitute (§8, §7.3 row): it stores honestly labelled HTML, archives and Office files. Port `RenderTier.decide` (`models/drive/RenderTier.kt:48`), polyglot checks included, rather than writing a second table. Take its caps from `GET /v1/media/policy` (§3; recommended policy).
4. **Tier C bytes are never decoded or parsed by the agent** (CC 5.3.2.6 table: PDF and `model_3d` are "C — download"; "HTML, Office, fonts, archives, TIFF, PSD, BMP, ICO, everything else" are "C — refuse"). No PDF, Office, HTML, archive or 3D parsing, and no text extraction from them. The only readable form CC 5.3.2.6 allows is a node rendition (an attested PNG, `derived_from`). **Renditions do not exist yet** (CIRISServer#614; the node says `renditions: false`), so **today Tier C has no readable form at all**. The agent may describe the file from its row (name, type, size, cohort, custody) and must refuse to read the contents.
5. **Tier B only as the sender-converted form** (CC 5.3.2.6 row "B — sender converts to a Tier-A form before hashing; raw arrival = C"). A raw HEIC, WebM, Opus or other Tier B arrival is Tier C (rule 4). SVG is "C for the bytes — never a client renderer"; its PNG rendition does not exist yet (#614).
6. **Text only as valid UTF-8, ≤ 1 MB, with bidi controls visible** (CC 5.3.2.6 `text/plain` row; §3). Reject invalid UTF-8 rather than repairing it. Before text reaches a prompt, show the bidi and zero-width controls (`RenderTier.showBidiControls`, `RenderTier.kt:202`). The node's own check covers only the first 64 KiB (`src/media_gate.rs:221-231`).
7. **Tier A images, audio and video**: whether to send *verified, sniffed, in-cap* originals to a model provider is the agent team's decision, not this document's. This client does **not** decode them at all until a rendition exists (§8, "One interim call"). Whatever is decided, the §3 caps apply, and `audio/mpeg`'s ID3 `APIC` is never decoded.
8. **Never feed raw bytes of an unverified, mismatched, polyglot or Tier C blob to a model or a tool.** That includes "just the first N bytes" and base64 in a prompt. This follows from rules 1, 3 and 4.
9. **Custody truth** (CIRISServer `FSD/FILE_CUSTODY.md` §1.1, §3; CC 3.1.3.3 on rc6): a receipt is "received", never "has it". `unknown` is never "none". `copies_observable: false` is never "1 copy". A `not_fetched` row is "on another device", never "missing" or "deleted".
10. **Scope does not widen.** Bytes read from a `self` or `family` room must not be written to a wider room, or out of the agent, by a tool without the person's act. *This needs verification by the agent team against CC 5.2 / CC 4.4.3; it is stated here as the obvious consequence of "structural invisibility", not as a cited rule.*

### 9.5 What not to assume

- That `/meta` exists on every node: it arrived in 0.5.216 (`FSD/ROSTER_AND_DRIVE_CRUD.md` §5.1), `content_digest` in 0.5.217, and `/custody` only in 0.5.218, which is unreleased. A bare 404 means "this node predates the route".
- That the listing carries digests: it does not (§9.3). Open or `/meta` each file.
- That `content_digest` is the author's claim: the node computed it (CIRISEdge#638).
- That `media_type`/`filename` are present: under `description: sealed` they are unknown (CIRISEdge#698), and in any case they are labels, not types (rule 3).
- That a receipt means the device still holds the file (§9.2), or that no receipt means it does not.
- That the node's write gate made a file safe: it checks honesty of the label, not tier, caps or polyglots (§8, §7.3 row).
- That the agent's own session can read the drive: only the owner's own, undelegated session can (§9.3). How an agent process holds such a session is the agent team's to answer.
- That a large file streams through the agent's port: it does not (§9.3).
- That `crossed: true` means the bytes are fetchable: `addressed: false` alongside it means they are not (`src/drive.rs:180-191`).

### 9.6 Checklist (turn each into a test)

1. A blob whose bytes hash to something other than `content_digest` is discarded and never reaches a model or tool.
2. A blob with no `content_digest` (node < 0.5.217, or a file > 64 MiB) is not consumed, and the agent says it could not be verified.
3. A received length different from `size` is refused before hashing.
4. A JPEG declared `image/png` is refused (sniffed ≠ declared), not re-labelled.
5. A PNG with bytes after `IEND`, and a JPEG with a ZIP EOCD in its last 64 KB, are both refused.
6. A PDF, a DOCX, an HTML file and a ZIP are each described from their row and **never** parsed, and no parser module is imported on that path.
7. A raw HEIC and a raw WebM are treated as Tier C.
8. `text/plain` with invalid UTF-8 is refused, and one with U+202E reaches the prompt with that control made visible.
9. A file over the §3 cap for its type is refused before decode.
10. A `not_fetched` row is reported as "on another device", and a `custody` `unknown` is never reported as "none".
11. A family or community open sends `cohort` and `room_id`.
12. With a delegated session, every drive route answers `403`, and the agent says so instead of retrying.
13. Through the agent port, an upload over 64 MiB is not attempted; large uploads go to the node URL as multipart with `size`.
14. No `custody:*`, `image:*`, `audio:*` or `video:*` dimension is emitted while the vendored registry lacks it.

**Not guaranteed by this document.** It does not guarantee that the server lines above stay true after `405acc17`, that renditions arrive, or that any Tier A decoder is safe in the agent's process. It also does not guarantee that a model provider's own image decoding is memory-safe: bytes sent to a provider are parsed by the provider's decoders, which this document cannot see.
