`fedcode_vectors.json` is copied byte for byte from CIRISVerify v22.2.0 (7146fa4),
`src/ciris-verify-core/tests/vectors/fedcode/fedcode_vectors.json` (CIRISVerify#329, regenerated in #331).
Never edit it here: update it from a new Verify tag and let `FedCodeVectorsTest` say
what changed. The agent and server pin the same file.

`call_frames_v1.json` is copied byte for byte from CIRISServer release/0.5.246 at
d662d99a (re-pinned from 92d5d864: adds the events section and an H.264 case), `tests/vectors/call_frames_v1.json` (CSD-134 §3, the call frames socket v1).
Never edit it here: update it from the server and let `CallFramesVectorsTest` say what
changed.
