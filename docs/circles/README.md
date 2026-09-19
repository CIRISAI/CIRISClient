# Circles explainer — build notes

`docs/circles.html` is the plain-words animation of how CIRIS moves what you
make: five circles, the five facts that travel with everything, sharing as a
step you choose, one person's brake, anyone-in-it takes it back.

## Where it comes from

Authored in Claude Design (project `3b5f0c4f-6415-486e-af54-5575613a5996`,
`Circles Explainer.dc.html`), from ciris.ai/contextual-integrity and
ciris.ai/safety. The design page is a thin shell around two React sources:

- `animations-v3.jsx` — the continuous-composition engine (one element tree,
  a pure function of authored time; `OM_SCENES` is the single source of the
  cue table; Space pauses; the bar seeks).
- `circles-scene.jsx` — the piece: `CirclesAnimation`, keyed to the 17 scene
  names in `OM_SCENES`.

Both are kept here verbatim.

## Why it is compiled, not transpiled at load

The design runtime (`support.js`) fetches the two `.jsx` files and transpiles
them in the browser with `@babel/standalone` — 3 MB downloaded and run on every
visit. For a published page that is the wrong shape. `compile.js` runs the
identical transform once (`@babel/standalone` **7.29.0**, presets
`react`+`typescript` — the same build the runtime pins, SRI
`sha384-m08KidiNqLdpJqLq95G/LEi8Qvjl/xUYll3QILypMoQ65QorJ9Lvtp2RXYGBFj1y`),
wraps each result exactly as the runtime evaluates it
(`new Function("React","module","exports","require", code)`), and writes
`circles-explainer.js` (~92 KB). The page then needs only React 18.3.1 from a
CDN, pinned with the runtime's own SRI hashes.

## Rebuild after editing a `.jsx`

```sh
cd docs/circles
curl -sSLo babel.min.js https://unpkg.com/@babel/standalone@7.29.0/babel.min.js
echo "sha384-$(openssl dgst -sha384 -binary babel.min.js | base64 -w0)"   # must match the SRI above
node compile.js
sed -i 's#src: "ciris-signet.svg"#src: "circles/ciris-signet.svg"#' circles-explainer.js
rm babel.min.js
```

The `sed` re-points the signet: the scene references it relative to the page,
and the page is one directory up.

`poster.jpg` is a frame from the `Born` scene (~77 s), used by the atlas card.
