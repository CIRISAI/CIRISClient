`fedcode_vectors.json` is copied byte for byte from CIRISVerify v22.2.0 (7146fa4),
`src/ciris-verify-core/tests/vectors/fedcode/fedcode_vectors.json` (CIRISVerify#329, regenerated in #331).
Never edit it here: update it from a new Verify tag and let `FedCodeVectorsTest` say
what changed. The agent and server pin the same file.

`call_frames_v1.json` is copied byte for byte from CIRISServer release/0.5.246 at
7b489909 (re-pinned from b08f3eca, 099cf247, ddc7bc54, 92d5d864, d662d99a and 27db3f74: the events section, person and fragmenting fields, a real decodable H.264 keyframe, `source_ts_ms` with `frame_seq` on received frames, and the note that `frame_seq` counts per layer, and the socket events as edge v45.0.0 sends them), `tests/vectors/call_frames_v1.json` (CSD-134 §3, the call frames socket v1).
Never edit it here: update it from the server and let `CallFramesVectorsTest` say what
changed.
