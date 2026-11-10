// The WebCodecs round-trip tests (calls/media) wait on real encoders and decoders; mocha's 2 s default is too short for them.
config.set({
    client: Object.assign({}, config.client, {
        mocha: Object.assign({}, (config.client || {}).mocha, { timeout: 60000 }),
    }),
});
