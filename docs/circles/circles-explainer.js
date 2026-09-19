// ---- animations-v3.jsx (compiled with @babel/standalone 7.29.0, presets react+typescript; identical to the dc-runtime's load-time transform) ----
(function(React, module, exports, require){
// @ds-adherence-ignore -- omelette starter scaffold (raw elements/hex/px by design)
// Copied omelette starter. Re-running copy_starter_component with this kind overwrites this file with the latest version (page content is unaffected).

/* BEGIN USAGE */
// animations-v3.jsx — continuous-composition animation engine.
//
// THE MODEL: the animation is ONE element tree rendered as a pure function
// of one authored-time axis. Nothing mounts or unmounts at section
// boundaries, so any element can move, morph, or persist across them by
// ordinary interpolation. The scene list (OM_SCENES) is the user-control
// view — names, order, playback durations — and the engine derives the cue
// table from it, so structure has exactly one source and cannot drift.
//
// API INDEX (every export is a window global):
//   <CompositionStage width height scenes={window.OM_SCENES}
//                     playback={window.OM_PLAYBACK} bg>
//     <Piece />   — ONE component, the whole animation
//   </CompositionStage>
//   useComposition() -> {T, CUES, time, duration, authoredTotal, playing}
//     T: authored seconds (warped per-section by user trims/speeds) —
//        key ALL choreography to T, never to wall-clock time
//     CUES: {SectionName: authoredStart} derived from OM_SCENES; an unknown
//        name returns NaN and raises a preview-only badge (never exports);
//        duplicate section names bind to the first occurrence
//   <Shot from={CUES.Build} to={CUES.Close}> — children visible between two
//     authored times (an authored hard cut in one line); children stay
//     mounted (media keeps its readiness) and are hidden outside the window
//   <Captions items={[{at, until?, text}, ...]} /> — ONE caption element,
//     at most one visible at a time, keyed to T; 'until' defaults to the
//     next item's 'at'; a last item with no 'until' stays to the end
//   WATERCOLOR (only when the Watercolor illustration skill is active —
//   otherwise ignore these entries). A painting is a function(p) written
//   against the paint kit, on a width x height sheet; it needs
//   watercolor_kit.js loaded by a <script> tag before this engine, must
//   be a stable function defined once (module scope, never an inline
//   arrow), and every component below renders <img> elements, so it all
//   exports by construction.
//   <WatercolorPainting painting={fn} from={CUES.X} to={CUES.Y} width height
//     seed scale quality style /> — the painting assembled from its own
//     STROKES: each wash / ink line / splatter is a separate layer
//     stacked over the paper, appearing in painting order between two
//     authored times (washes bloom in, ink draws tip to tail). This is the
//     default way to show a watercolor being painted. It keeps the sheet's
//     aspect ratio (size it with style, e.g. {position:'absolute', left,
//     top, width}). scale is the layers' render resolution over width x
//     height (default 1, a deliberate weight-over-dpi trade — raise it
//     toward the zoom factor if the composition zooms into the painting,
//     or toward the devicePixelRatio for a hero-sized sheet); quality is
//     0..1 layer image quality (default 0.92; 1 is the encoder's maximum).
//   useWatercolorLayers(fn, {width, height, seed, scale, quality}) -> L
//     (null if the kit isn't loaded — load watercolor_kit.js before the
//     engine — or if the painting fails to build) — the painting taken apart into strokes, for
//     choreography beyond in-order painting: L.count strokes, L.kind(i)
//     ('wash' | 'gradedWash' | 'glaze' | 'ink' | 'hatch' | 'splatter' |
//     'dryStroke' | 'reserve' | 'caption'), L.span(i) = the stroke's
//     {from, to} share of the painting's 0..1 timeline; call L.warm()
//     once after load so finished strokes pre-render off the critical
//     path (WatercolorPainting does this itself). Compose with:
//   <WatercolorSheet layers={L} style>children</WatercolorSheet> — the
//     paper the strokes sit on (keeps the sheet's aspect ratio), and
//   <WatercolorStroke index={i} at={0..1} style /> — stroke i as its own
//     element, placed where it was painted; at is its painting progress
//     (0 hidden, 1 finished — drive it from T with animate()); style lets
//     you move, scale, rotate, or fade the stroke (transform / opacity).
//     Strokes are paint, so they multiply: overlapping strokes darken
//     where they cross, as in the still image, within a few 8-bit levels
//     (tighter still at quality 1). The sheet clips to its
//     box — for strokes that fly in from outside it, set
//     style={{overflow: 'visible'}} on the WatercolorSheet. 'reserve' strokes
//     are erasures (lifted paper) — keep them where they were painted and
//     reveal them in order after the strokes they erase; moving an erase
//     around has no sensible meaning.
//   <WatercolorReveal painting={fn} from={CUES.X} to={CUES.Y} width height
//     seed steps scale format quality style />, or <WatercolorReveal
//     frames={[src, ...]} from to /> — the whole painting as ONE flat
//     image that paints on (frames pre-baked in the background, so it is
//     the lightest option and the one to zoom or pan over as a single
//     picture). Prefer WatercolorPainting when the strokes themselves
//     should appear one by one or be individually animated. format is
//     the image MIME type (default image/jpeg), quality 0..1 (default
//     0.88). Frames bake at width x height times scale (default: the
//     device pixel ratio, capped at 2) — if the composition zooms INTO
//     the painting, raise scale toward the maximum zoom so frames stay
//     crisp. The kit caps a sheet at ~12M pixels and the components clamp
//     scale to stay under it; exported video sharpness also depends on
//     the export dialog's own resolution choice.
//   Motion: Easing.{linear, easeIn|Out|InOutQuad/Cubic/Quart/Expo/Sine,
//     easeIn|Out|InOutBack, easeOutElastic}, interpolate(input, output, ease),
//     animate({from, to, start, end, ease}) -> fn(T), clamp(v, min, max)
//   Plumbing (rarely needed): Stage, PlaybackBar, TimelineContext,
//     useTime, useTimeline
//   Seek event (host/export transport): 'data-om-seek-to-time-frame',
//     detail {time, sync, playing} — the stage owns it; never implement it
//     yourself
//
// THE AUTHORING CONTRACT — this is what makes the host timeline's trim and
// speed gestures write back into YOUR file, so follow it exactly:
//   1. Declare the scene list as a JSON string literal in a plain inline
//      <script> of the main document (NOT type="text/babel", NOT a sibling
//      .jsx — only vanilla inline scripts are addressable for write-back):
//        <script>window.OM_SCENES = '[{"name":"Opening","dur":3,"desc":"The logo fades in and the title settles"},{"name":"Build","dur":5,"desc":"Bars grow to their final values"}]';</script>
//      Give every entry a "desc": one short plain-words sentence saying
//      what happens in that section. The user reads it in the timeline's
//      section popover — keep it true whenever you edit the section.
//   2. Pass the string through untouched:
//        <CompositionStage scenes={window.OM_SCENES} ...>
//   3. ALSO declare the playback setting the same way:
//        <script>window.OM_PLAYBACK = '{"mode":"loop"}';</script>
//      and pass it through untouched (values: '{"mode":"loop"}' or
//      '{"mode":"times","count":N}'; omitting keeps loop behavior but
//      leaves the host Repeat control read-only for this document).
//   IMPORTANT — the exportable-video contract: CompositionStage/Stage OWNS
//   it (the data-om-exportable-video-with-duration-secs attribute, the
//   data-om-seek-to-time-frame listener, the svg/foreignObject wrapper,
//   and font inlining). NEVER put the exportable attribute on any other
//   element — a second "exportable root" makes the host timeline and the
//   video exporter bind to the wrong element, and playback control /
//   export silently break.
//
// HOW TIME WORKS: each OM_SCENES entry is a named slice of the authored
// timeline. CUES.Name is that section's authored start (the running sum of
// authored lengths, in literal order). useComposition().T is the authored
// clock: when the user trims or speeds a section on the host timeline, the
// engine replays that section's SAME authored slice over the new playback
// length — your choreography retimes, never cuts off. The optional "nat"
// field on an entry is the engine's authored-length anchor — the host
// timeline stamps it on the first retime; don't set it by hand.
//
// CUE-FIRST DISCIPLINE (what makes a piece read as one continuous video):
//   1. Write the OM_SCENES literal FIRST — it is the piece's outline.
//   2. One helper component per section for readability, but ALL of them
//      render ALL the time inside the one tree, keyed to CUES — never
//      conditionally mounted per section.
//   3. Define exactly three motion helpers up front (e.g.
//      MOTION = {enter, draw, pop} wrapping Easing curves) and use no
//      easing or transform outside them; one caption element, one visible
//      at a time (<Captions> has this built in).
//   A shared element that crosses a boundary is just motion whose start
//   and end straddle a cue: animate({from, to, start: CUES.Build - 0.4,
//   end: CUES.Build + 0.6})(T) glides through the boundary, and a user
//   slowing either section slows the glide without breaking it.
//
// RENDER FROM T ONLY: the exporter seeks each frame with a synchronous
// commit and may serialize the stage the moment the seek event returns —
// anything painted from useEffect or your own requestAnimationFrame lags
// that commit and exports stale. Render everything visible from T and this
// is automatic. A seeked frame is a deterministic render at that time.
//
// HARD CUTS are content now, not structure: wrap a shot's elements in
// <Shot from to> (visibility toggles at the cues; children stay mounted so
// images and videos hold their readiness). Shot also doubles as the
// perf gate for heavy far-away beats.
//
// LOOP SEAMS are the one surviving boundary rule: a looping piece shows
// its last authored frame immediately before its first — make them match
// (settle your choreography by authoredTotal, open it at 0).
//
// DIAGNOSTICS: choreography that references an unknown section name (a
// rename or deletion in OM_SCENES) shows a badge below the stage in the
// preview, outside the exportable svg — visible in preview screenshots,
// never in the exported video. An OM_SCENES section with no choreography
// keyed to it is a valid empty beat, not an error.
/* END USAGE */

// ─────────────────────────────────────────────────────────────────────────────

// ── Easing functions (hand-rolled, Popmotion-style) ─────────────────────────
// All easings take t ∈ [0,1] and return eased t ∈ [0,1] (may overshoot for back/elastic).
const Easing = {
  linear: t => t,
  // Quad
  easeInQuad: t => t * t,
  easeOutQuad: t => t * (2 - t),
  easeInOutQuad: t => t < 0.5 ? 2 * t * t : -1 + (4 - 2 * t) * t,
  // Cubic
  easeInCubic: t => t * t * t,
  easeOutCubic: t => --t * t * t + 1,
  easeInOutCubic: t => t < 0.5 ? 4 * t * t * t : (t - 1) * (2 * t - 2) * (2 * t - 2) + 1,
  // Quart
  easeInQuart: t => t * t * t * t,
  easeOutQuart: t => 1 - --t * t * t * t,
  easeInOutQuart: t => t < 0.5 ? 8 * t * t * t * t : 1 - 8 * --t * t * t * t,
  // Expo
  easeInExpo: t => t === 0 ? 0 : Math.pow(2, 10 * (t - 1)),
  easeOutExpo: t => t === 1 ? 1 : 1 - Math.pow(2, -10 * t),
  easeInOutExpo: t => {
    if (t === 0) return 0;
    if (t === 1) return 1;
    if (t < 0.5) return 0.5 * Math.pow(2, 20 * t - 10);
    return 1 - 0.5 * Math.pow(2, -20 * t + 10);
  },
  // Sine
  easeInSine: t => 1 - Math.cos(t * Math.PI / 2),
  easeOutSine: t => Math.sin(t * Math.PI / 2),
  easeInOutSine: t => -(Math.cos(Math.PI * t) - 1) / 2,
  // Back (overshoot)
  easeOutBack: t => {
    const c1 = 1.70158,
      c3 = c1 + 1;
    return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
  },
  easeInBack: t => {
    const c1 = 1.70158,
      c3 = c1 + 1;
    return c3 * t * t * t - c1 * t * t;
  },
  easeInOutBack: t => {
    const c1 = 1.70158,
      c2 = c1 * 1.525;
    return t < 0.5 ? Math.pow(2 * t, 2) * ((c2 + 1) * 2 * t - c2) / 2 : (Math.pow(2 * t - 2, 2) * ((c2 + 1) * (t * 2 - 2) + c2) + 2) / 2;
  },
  // Elastic
  easeOutElastic: t => {
    const c4 = 2 * Math.PI / 3;
    if (t === 0) return 0;
    if (t === 1) return 1;
    return Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * c4) + 1;
  }
};

// ── Core interpolation helpers ──────────────────────────────────────────────

// Clamp a value to [min, max]
const clamp = (v, min, max) => Math.max(min, Math.min(max, v));

// interpolate([0, 0.5, 1], [0, 100, 50], ease?) -> fn(t)
// Popmotion-style: linearly maps t across input keyframes to output values,
// with optional easing per segment (single fn or array of fns).
function interpolate(input, output, ease = Easing.linear) {
  return t => {
    if (t <= input[0]) return output[0];
    if (t >= input[input.length - 1]) return output[output.length - 1];
    for (let i = 0; i < input.length - 1; i++) {
      if (t >= input[i] && t <= input[i + 1]) {
        const span = input[i + 1] - input[i];
        const local = span === 0 ? 0 : (t - input[i]) / span;
        const easeFn = Array.isArray(ease) ? ease[i] || Easing.linear : ease;
        const eased = easeFn(local);
        return output[i] + (output[i + 1] - output[i]) * eased;
      }
    }
    return output[output.length - 1];
  };
}

// animate({from, to, start, end, ease})(t) — simpler single-segment tween.
// Returns `from` before `start`, `to` after `end`.
function animate({
  from = 0,
  to = 1,
  start = 0,
  end = 1,
  ease = Easing.easeInOutCubic
}) {
  return t => {
    if (t <= start) return from;
    if (t >= end) return to;
    const local = (t - start) / (end - start);
    return from + (to - from) * ease(local);
  };
}

// ── Timeline context ────────────────────────────────────────────────────────

const TimelineContext = React.createContext({
  time: 0,
  duration: 10,
  playing: false
});
const useTime = () => React.useContext(TimelineContext).time;
const useTimeline = () => React.useContext(TimelineContext);

// How long a marked (detail.playing === true) host seek keeps the
// external-playback latch alive with no successor. The host play bar's
// seek pump is one-in-flight/latest-wins, so its inter-seek gap is tens
// of milliseconds in the worst case — 400ms is far above that, so a
// marked stream that dies mid-play decays the latch promptly.
var SS_EXT_PLAY_MS = 400;

// ── Font inlining ───────────────────────────────────────────────────────────
// Copy every @font-face rule from the page into a <style> inside the svg's
// foreignObject, with font URLs rewritten to data: URLs. Makes the svg
// self-describing so serializing it alone (video export fast path) still
// renders with the right fonts. Sets data-om-fonts-inlined on the svg when
// done so the exporter can wait for it.

function useInlineFontsInto(svgRef) {
  React.useEffect(() => {
    const svg = svgRef.current;
    const host = svg && svg.querySelector('foreignObject > div');
    if (!svg || !host) return;
    let cancelled = false;
    (async () => {
      const rules = [];
      for (const ss of document.styleSheets) {
        let cssRules;
        try {
          cssRules = ss.cssRules;
        } catch {
          // Cross-origin sheet without crossorigin attr (e.g. the standard
          // fonts.googleapis.com <link>) — fetch the CSS text directly and
          // regex-extract the @font-face blocks.
          if (ss.href) {
            try {
              const txt = await fetch(ss.href).then(r => {
                if (!r.ok) throw 0;
                return r.text();
              });
              for (const ff of txt.match(/@font-face\s*{[^}]*}/g) || []) rules.push({
                css: ff,
                base: ss.href
              });
            } catch {}
          }
          continue;
        }
        if (!cssRules) continue;
        for (const r of cssRules) {
          if (r.type === CSSRule.FONT_FACE_RULE) {
            rules.push({
              css: r.cssText,
              base: ss.href || location.href
            });
          }
        }
      }
      const toDataURL = url => fetch(url).then(r => {
        if (!r.ok) throw 0;
        return r.blob();
      }).then(b => new Promise(res => {
        const fr = new FileReader();
        fr.onload = () => res(fr.result);
        fr.onerror = () => res(url);
        fr.readAsDataURL(b);
      })).catch(() => url);
      const parts = await Promise.all(rules.map(async ({
        css,
        base
      }) => {
        const re = /url\((['"]?)([^'")]+)\1\)/g;
        let out = css,
          m;
        while (m = re.exec(css)) {
          const u = m[2];
          if (u.startsWith('data:')) continue;
          let abs;
          try {
            abs = new URL(u, base).href;
          } catch {
            continue;
          }
          out = out.split(m[0]).join(`url("${await toDataURL(abs)}")`);
        }
        return out;
      }));
      if (cancelled || !parts.length) {
        svg.setAttribute('data-om-fonts-inlined', 'true');
        return;
      }
      const style = document.createElement('style');
      style.textContent = parts.join('\n');
      host.insertBefore(style, host.firstChild);
      svg.setAttribute('data-om-fonts-inlined', 'true');
    })();
    return () => {
      cancelled = true;
    };
  }, []);
}
function Stage({
  width = 1280,
  height = 720,
  duration = 10,
  background = '#f6f4ef',
  fps = 60,
  loop = true,
  autoplay = true,
  // Parsed playback object ({mode:'loop'} | {mode:'times',count:N}) or
  // null. When present it overrides the legacy loop prop — CompositionStage
  // passes the validated value from the OM_PLAYBACK authoring contract.
  playback = null,
  persistKey = 'animstage-v3',
  children
}) {
  // Props arrive as strings when Stage is mounted via <x-import> (DC
  // projects) — coerce so style={{width}} gets a number React can px-ify.
  width = +width || 1280;
  height = +height || 720;
  duration = +duration || 10;
  fps = +fps || 60;
  if (typeof loop === 'string') loop = loop !== 'false';
  if (typeof autoplay === 'string') autoplay = autoplay !== 'false';
  const playTimes = playback && playback.mode === 'times' ? playback.count : null;
  const loopEff = playback ? playback.mode === 'loop' : loop;
  const [time, setTime] = React.useState(() => {
    try {
      const v = parseFloat(localStorage.getItem(persistKey + ':t') || '0');
      return isFinite(v) ? clamp(v, 0, duration) : 0;
    } catch {
      return 0;
    }
  });
  const [playing, setPlaying] = React.useState(autoplay);
  // The external-playback latch: true while the HOST play bar is driving
  // time forward as genuine continuous playback (its play-loop seeks
  // carry detail.playing === true). The engine's own clock stays paused
  // the whole time — exactly one clock ever drives — so this is a
  // separate bit, not a second meaning for `playing`. Set and cleared
  // in the seek handler below; decays via SS_EXT_PLAY_MS when the
  // marked stream stops without a parting unmarked seek.
  const [extPlay, setExtPlay] = React.useState(false);
  const extPlayTimerRef = React.useRef(null);
  const [hoverTime, setHoverTime] = React.useState(null);
  const [scale, setScale] = React.useState(1);
  const stageRef = React.useRef(null);
  const canvasRef = React.useRef(null);
  const rafRef = React.useRef(null);
  const lastTsRef = React.useRef(null);

  // Persist playhead
  React.useEffect(() => {
    try {
      localStorage.setItem(persistKey + ':t', String(time));
    } catch {}
  }, [time, persistKey]);

  // Auto-scale to fit viewport
  React.useEffect(() => {
    if (!stageRef.current) return;
    const el = stageRef.current;
    const measure = () => {
      const barH = 44; // playback bar height
      const s = Math.min(el.clientWidth / width, (el.clientHeight - barH) / height);
      setScale(Math.max(0.05, s));
    };
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    window.addEventListener('resize', measure);
    return () => {
      ro.disconnect();
      window.removeEventListener('resize', measure);
    };
  }, [width, height]);

  // Passes completed since playback last started. Lives in a ref so the
  // per-frame wrap can count without re-running this effect; reset on
  // every (re)start so a fresh play (or a host restart) gets the full
  // run count again.
  const passesRef = React.useRef(0);

  // Animation loop
  React.useEffect(() => {
    if (!playing) {
      lastTsRef.current = null;
      return;
    }
    passesRef.current = 0;
    const step = ts => {
      if (lastTsRef.current == null) lastTsRef.current = ts;
      const dt = (ts - lastTsRef.current) / 1000;
      lastTsRef.current = ts;
      setTime(t => {
        let next = t + dt;
        if (next >= duration) {
          if (playTimes !== null) {
            // Play N times then hold the last frame — the partial pass a
            // mid-timeline start produces counts as a pass, so the piece
            // never runs longer than N full durations.
            passesRef.current += 1;
            if (passesRef.current >= playTimes) {
              next = duration;
              setPlaying(false);
            } else {
              next = next % duration;
            }
          } else if (loopEff) {
            next = next % duration;
          } else {
            next = duration;
            setPlaying(false);
          }
        }
        return next;
      });
      rafRef.current = requestAnimationFrame(step);
    };
    rafRef.current = requestAnimationFrame(step);
    return () => {
      if (rafRef.current) cancelAnimationFrame(rafRef.current);
      lastTsRef.current = null;
    };
  }, [playing, duration, loopEff, playTimes]);

  // Keyboard: space = play/pause, ← → = seek
  React.useEffect(() => {
    const onKey = e => {
      if (e.target && (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA')) return;
      if (e.code === 'Space') {
        e.preventDefault();
        setPlaying(p => !p);
      } else if (e.code === 'ArrowLeft') {
        setTime(t => clamp(t - (e.shiftKey ? 1 : 0.1), 0, duration));
      } else if (e.code === 'ArrowRight') {
        setTime(t => clamp(t + (e.shiftKey ? 1 : 0.1), 0, duration));
      } else if (e.key === '0' || e.code === 'Home') {
        setTime(0);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [duration]);

  // Video-export protocol + the editor's play bar: hosts dispatch this
  // event per frame; pause + sync the playhead so the frame shows exactly
  // that timestamp. The host play bar marks its play-loop seeks with
  // detail.playing === true — the mark latches extPlay (playback is
  // playback even when a host clock drives it), while ANY unmarked seek
  // (scrub, step, export frame, the transport's pause park) clears the
  // latch in the same commit it retimes, so a seeked frame still renders
  // exactly one scene's state. The engine's own clock pauses either way.
  React.useEffect(() => {
    const el = canvasRef.current;
    if (!el) return;
    // Sync-seek capability: a dispatcher that marks its seek with
    // detail.sync === true gets the commit applied via ReactDOM.flushSync,
    // so the stage DOM reflects the seeked frame the moment dispatchEvent
    // returns. The video exporter keys off the data-om-sync-seek
    // advertisement to drop its two-display-refresh settle (that wait only
    // exists to let React's async commit land — serialization needs the
    // committed DOM, not the paint). Feature-detected: a runtime without
    // ReactDOM.flushSync never advertises and every seek takes the async
    // path. Unmarked seeks (scrubs, the host play bar) stay async — a
    // forced sync render per pointermove would tax the editor for no one.
    const canSyncSeek = typeof ReactDOM !== 'undefined' && typeof ReactDOM.flushSync === 'function';
    const onSeek = e => {
      const apply = () => {
        setPlaying(false);
        const hostPlay = !!(e.detail && e.detail.playing === true);
        if (extPlayTimerRef.current) {
          clearTimeout(extPlayTimerRef.current);
          extPlayTimerRef.current = null;
        }
        if (hostPlay) {
          // Watchdog: the latch is only as alive as its seek stream. If the
          // host stops without a parting seek (tab jank, bar unmount), the
          // latch decays on its own rather than stranding extPlaying true.
          extPlayTimerRef.current = setTimeout(() => {
            extPlayTimerRef.current = null;
            setExtPlay(false);
          }, SS_EXT_PLAY_MS);
        }
        setExtPlay(hostPlay);
        setTime(clamp(e.detail.time, 0, duration));
      };
      // flushSync is safe here: a native DOM listener runs outside React's
      // lifecycle, and the exporter's dispatchEvent is synchronous, so the
      // commit lands in the same JS task — the engine's own rAF loop can
      // never interleave between seek and serialize.
      if (canSyncSeek && e.detail && e.detail.sync === true) {
        ReactDOM.flushSync(apply);
      } else {
        apply();
      }
    };
    el.addEventListener('data-om-seek-to-time-frame', onSeek);
    if (canSyncSeek) el.setAttribute('data-om-sync-seek', 'true');
    return () => {
      el.removeEventListener('data-om-seek-to-time-frame', onSeek);
      el.removeAttribute('data-om-sync-seek');
      if (extPlayTimerRef.current) {
        clearTimeout(extPlayTimerRef.current);
        extPlayTimerRef.current = null;
      }
      // Drop the latch too: this cleanup runs on every duration change
      // (an agent edit can retime mid-host-play, no gesture involved) and
      // the new effect instance arms no watchdog — clearing only the
      // timer could strand extPlay true forever if the marked stream died
      // in the gap. Fail toward cut: the next marked seek re-latches.
      setExtPlay(false);
    };
  }, [duration]);

  // Inline @font-face rules into the svg's foreignObject so the svg is
  // self-describing — serializing it alone (for video export) then renders
  // with the right fonts. Sets data-om-fonts-inlined once done.
  useInlineFontsInto(canvasRef);
  const displayTime = hoverTime != null ? hoverTime : time;
  const ctxValue = React.useMemo(
  // extPlaying is ADDITIVE: "time is advancing under an external
  // driver's continuous playback". `playing` keeps meaning the
  // engine's OWN clock — the hidden PlaybackBar glyph (and through it
  // the host's clock-reporter/adoption channel) reads that — and
  // CompositionClock is the one consumer that widens to either.
  () => ({
    time: displayTime,
    duration,
    playing,
    extPlaying: extPlay,
    setTime,
    setPlaying
  }), [displayTime, duration, playing, extPlay]);
  return (
    /*#__PURE__*/
    // data-om-starter: inert presence marker — Claude Design's starter-usage
    // probe reads it; it renders nothing. Keep it on this root element.
    React.createElement("div", {
      ref: stageRef,
      "data-om-starter": "animations-v3",
      style: {
        position: 'absolute',
        inset: 0,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        background: '#0a0a0a',
        fontFamily: 'Inter, system-ui, sans-serif'
      }
    }, /*#__PURE__*/React.createElement("div", {
      style: {
        flex: 1,
        width: '100%',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        overflow: 'hidden',
        minHeight: 0
      }
    }, /*#__PURE__*/React.createElement("svg", {
      ref: canvasRef,
      width: width,
      height: height,
      "data-om-exportable-video-with-duration-secs": duration,
      style: {
        transform: `scale(${scale})`,
        transformOrigin: 'center',
        flexShrink: 0,
        boxShadow: '0 20px 60px rgba(0,0,0,0.4)',
        display: 'block'
      }
    }, /*#__PURE__*/React.createElement("foreignObject", {
      x: "0",
      y: "0",
      width: "100%",
      height: "100%"
    }, /*#__PURE__*/React.createElement("div", {
      xmlns: "http://www.w3.org/1999/xhtml",
      style: {
        width,
        height,
        background,
        position: 'relative',
        overflow: 'hidden'
      }
    }, /*#__PURE__*/React.createElement(TimelineContext.Provider, {
      value: ctxValue
    }, children))))), /*#__PURE__*/React.createElement(PlaybackBar, {
      time: displayTime,
      actualTime: time,
      duration: duration,
      playing: playing,
      onPlayPause: () => setPlaying(p => !p),
      onReset: () => {
        setTime(0);
      },
      onSeek: t => setTime(t),
      onHover: t => setHoverTime(t)
    }))
  );
}

// ── Playback bar ────────────────────────────────────────────────────────────
// Play/pause, return-to-begin, scrub track, time display.
// Uses fixed-width time fields so layout doesn't thrash.

function PlaybackBar({
  time,
  duration,
  playing,
  onPlayPause,
  onReset,
  onSeek,
  onHover
}) {
  const trackRef = React.useRef(null);
  const [dragging, setDragging] = React.useState(false);
  const timeFromEvent = React.useCallback(e => {
    const rect = trackRef.current.getBoundingClientRect();
    const x = clamp((e.clientX - rect.left) / rect.width, 0, 1);
    return x * duration;
  }, [duration]);
  const onTrackMove = e => {
    if (!trackRef.current) return;
    const t = timeFromEvent(e);
    if (dragging) {
      onSeek(t);
    } else {
      onHover(t);
    }
  };
  const onTrackLeave = () => {
    if (!dragging) onHover(null);
  };
  const onTrackDown = e => {
    setDragging(true);
    const t = timeFromEvent(e);
    onSeek(t);
    onHover(null);
  };
  React.useEffect(() => {
    if (!dragging) return;
    const onUp = () => setDragging(false);
    const onMove = e => {
      if (!trackRef.current) return;
      const t = timeFromEvent(e);
      onSeek(t);
    };
    window.addEventListener('mouseup', onUp);
    window.addEventListener('mousemove', onMove);
    return () => {
      window.removeEventListener('mouseup', onUp);
      window.removeEventListener('mousemove', onMove);
    };
  }, [dragging, timeFromEvent, onSeek]);
  const pct = duration > 0 ? time / duration * 100 : 0;
  const fmt = t => {
    const total = Math.max(0, t);
    const m = Math.floor(total / 60);
    const s = Math.floor(total % 60);
    const cs = Math.floor(total * 100 % 100);
    return `${String(m).padStart(1, '0')}:${String(s).padStart(2, '0')}.${String(cs).padStart(2, '0')}`;
  };
  const mono = 'JetBrains Mono, ui-monospace, SFMono-Regular, monospace';
  return /*#__PURE__*/React.createElement("div", {
    "data-omelette-chrome": true,
    style: {
      // Slimmed to visually match the host editor bar's basic row (the
      // single-scrubber look): transport first, tighter metrics, quieter
      // chrome. Shown only outside the app — the host bar suppresses this
      // whenever it is present.
      display: 'flex',
      alignItems: 'center',
      gap: 10,
      padding: '6px 12px',
      background: 'rgba(20,20,20,0.92)',
      borderTop: '1px solid rgba(255,255,255,0.08)',
      width: '100%',
      maxWidth: 680,
      alignSelf: 'center',
      borderRadius: 6,
      color: '#f6f4ef',
      fontFamily: 'Inter, system-ui, sans-serif',
      userSelect: 'none',
      flexShrink: 0
    }
  }, /*#__PURE__*/React.createElement(IconButton, {
    onClick: onPlayPause,
    title: "Play/pause (space)"
  }, playing ? /*#__PURE__*/React.createElement("svg", {
    width: "14",
    height: "14",
    viewBox: "0 0 14 14",
    fill: "none"
  }, /*#__PURE__*/React.createElement("rect", {
    x: "3",
    y: "2",
    width: "3",
    height: "10",
    fill: "currentColor"
  }), /*#__PURE__*/React.createElement("rect", {
    x: "8",
    y: "2",
    width: "3",
    height: "10",
    fill: "currentColor"
  })) : /*#__PURE__*/React.createElement("svg", {
    width: "14",
    height: "14",
    viewBox: "0 0 14 14",
    fill: "none"
  }, /*#__PURE__*/React.createElement("path", {
    d: "M3 2l9 5-9 5V2z",
    fill: "currentColor"
  }))), /*#__PURE__*/React.createElement(IconButton, {
    onClick: onReset,
    title: "Return to start (0)"
  }, /*#__PURE__*/React.createElement("svg", {
    width: "14",
    height: "14",
    viewBox: "0 0 14 14",
    fill: "none"
  }, /*#__PURE__*/React.createElement("path", {
    d: "M3 2v10M12 2L5 7l7 5V2z",
    stroke: "currentColor",
    strokeWidth: "1.5",
    strokeLinejoin: "round",
    strokeLinecap: "round"
  }))), /*#__PURE__*/React.createElement("div", {
    style: {
      fontFamily: mono,
      fontSize: 12,
      fontVariantNumeric: 'tabular-nums',
      width: 64,
      textAlign: 'right',
      color: '#f6f4ef'
    }
  }, fmt(time)), /*#__PURE__*/React.createElement("div", {
    ref: trackRef,
    onMouseMove: onTrackMove,
    onMouseLeave: onTrackLeave,
    onMouseDown: onTrackDown,
    style: {
      flex: 1,
      height: 22,
      position: 'relative',
      cursor: 'pointer',
      display: 'flex',
      alignItems: 'center'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: 0,
      right: 0,
      height: 4,
      background: 'rgba(255,255,255,0.12)',
      borderRadius: 2
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: 0,
      width: `${pct}%`,
      height: 4,
      background: 'oklch(72% 0.12 250)',
      borderRadius: 2
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: `${pct}%`,
      top: '50%',
      width: 12,
      height: 12,
      marginLeft: -6,
      marginTop: -6,
      background: '#fff',
      borderRadius: 6,
      boxShadow: '0 2px 4px rgba(0,0,0,0.4)'
    }
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      fontFamily: mono,
      fontSize: 12,
      fontVariantNumeric: 'tabular-nums',
      width: 64,
      textAlign: 'left',
      color: 'rgba(246,244,239,0.55)'
    }
  }, fmt(duration)), typeof VideoEncoder !== 'undefined' && /*#__PURE__*/React.createElement(IconButton, {
    title: "Export video",
    onClick: () => window.parent.postMessage({
      type: 'omelette:request-video-export'
    }, '*')
  }, /*#__PURE__*/React.createElement("svg", {
    width: "14",
    height: "14",
    viewBox: "0 0 14 14",
    fill: "none"
  }, /*#__PURE__*/React.createElement("path", {
    d: "M7 2v7m0 0L4 6m3 3l3-3M2 12h10",
    stroke: "currentColor",
    strokeWidth: "1.5",
    strokeLinecap: "round",
    strokeLinejoin: "round"
  }))));
}
function IconButton({
  children,
  onClick,
  title
}) {
  const [hover, setHover] = React.useState(false);
  return /*#__PURE__*/React.createElement("button", {
    onClick: onClick,
    title: title,
    onMouseEnter: () => setHover(true),
    onMouseLeave: () => setHover(false),
    style: {
      width: 24,
      height: 24,
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      background: hover ? 'rgba(255,255,255,0.12)' : 'rgba(255,255,255,0.04)',
      border: '1px solid rgba(255,255,255,0.1)',
      borderRadius: 5,
      color: '#f6f4ef',
      cursor: 'pointer',
      padding: 0,
      transition: 'background 120ms'
    }
  }, children);
}

// ── Scene-list plumbing ──────────────────────────────────────────────────
// Guest-side validation of a scene list (the engine's own inputs: the
// authored prop, and host-dispatched updates). Mirrors the host parser's
// shape rules and constants — keep in sync with parseTimelineScenes in
// apps/web/src/shared/timeline.ts (16KB raw cap, 50 entries, dur finite in
// (0, 300]); returns null on any violation.
function ssParse(raw) {
  if (typeof raw !== 'string' || !raw || raw.length > 16 * 1024) return null;
  var parsed;
  try {
    parsed = JSON.parse(raw);
  } catch (e) {
    return null;
  }
  if (!Array.isArray(parsed) || parsed.length === 0 || parsed.length > 50) return null;
  for (var i = 0; i < parsed.length; i++) {
    var s = parsed[i];
    if (typeof s !== 'object' || s === null) return null;
    if (typeof s.name !== 'string' || typeof s.dur !== 'number') return null;
    if (!isFinite(s.dur) || s.dur <= 0 || s.dur > 300) return null;
  }
  return parsed;
}

// Guest-side validation of the playback value — mirrors the host parser
// (shared/timeline.ts parseTimelinePlayback): {"mode":"loop"} or
// {"mode":"times","count":1..99}, strict all-or-nothing, null otherwise.
// Callers treat null as the loop default.
function ppParse(raw) {
  if (typeof raw !== 'string' || !raw || raw.length > 256) return null;
  var parsed;
  try {
    parsed = JSON.parse(raw);
  } catch (e) {
    return null;
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return null;
  var keys = Object.keys(parsed);
  if (parsed.mode === 'loop') return keys.length === 1 ? {
    mode: 'loop'
  } : null;
  if (parsed.mode === 'times') {
    if (keys.length !== 2) return null;
    var c = parsed.count;
    if (typeof c !== 'number' || c !== Math.floor(c) || c < 1 || c > 99) return null;
    return {
      mode: 'times',
      count: c
    };
  }
  return null;
}

// Stamps the playback attribute VERBATIM from the authored raw string (the
// host's write-back anchors on that exact value) and listens for the
// host's post-write update event. Same shape as SceneSync; only rendered
// when the document authors a playback literal — an absent contract means
// the attribute stays absent and the document plays its default.
function PlaybackSync(props) {
  var ref = React.useRef(null);
  var raw = props.raw;
  var onUpdate = props.onUpdate;
  React.useEffect(function () {
    var el = ref.current;
    if (!el) return;
    var root = el.closest('[data-om-exportable-video-with-duration-secs]');
    if (!root) return;
    root.setAttribute('data-om-timeline-playback', raw);
    var onEvent = function (e) {
      var next = e && e.detail;
      if (ppParse(next)) onUpdate(next);
    };
    root.addEventListener('data-om-timeline-playback-update', onEvent);
    return function () {
      root.removeEventListener('data-om-timeline-playback-update', onEvent);
      root.removeAttribute('data-om-timeline-playback');
    };
  }, [raw, onUpdate]);
  return /*#__PURE__*/React.createElement("div", {
    ref: ref,
    style: {
      display: 'none'
    }
  });
}

// Renders inside the Stage (so it can reach the exportable root via
// closest()): stamps the scenes attribute VERBATIM from the current raw
// string — the host's write-back anchors on that exact value — and listens
// for the host's post-write update event.
function SceneSync(props) {
  var ref = React.useRef(null);
  var raw = props.raw;
  var onUpdate = props.onUpdate;
  React.useEffect(function () {
    var el = ref.current;
    if (!el) return;
    var root = el.closest('[data-om-exportable-video-with-duration-secs]');
    if (!root) return;
    root.setAttribute('data-om-timeline-scenes', raw);
    var onEvent = function (e) {
      var next = e && e.detail;
      // Ignore anything that doesn't validate — a bad update must not tear
      // down a working composition.
      if (ssParse(next)) onUpdate(next);
    };
    root.addEventListener('data-om-timeline-scenes-update', onEvent);
    return function () {
      root.removeEventListener('data-om-timeline-scenes-update', onEvent);
      root.removeAttribute('data-om-timeline-scenes');
    };
  }, [raw, onUpdate]);
  return /*#__PURE__*/React.createElement("div", {
    ref: ref,
    style: {
      display: 'none'
    }
  });
}

// ── Continuous composition ──────────────────────────────────────────────

var CompositionContext = React.createContext(null);
function useComposition() {
  var ctx = React.useContext(CompositionContext);
  if (!ctx) throw new Error('useComposition() must be called inside <CompositionStage>');
  return ctx;
}
function ccDerive(scenes) {
  var playStart = 0;
  var authStart = 0;
  var sections = [];
  var table = Object.create(null);
  for (var i = 0; i < scenes.length; i++) {
    var s = scenes[i];
    var nat = typeof s.nat === 'number' && isFinite(s.nat) && s.nat > 0 ? s.nat : s.dur;
    sections.push({
      name: s.name,
      playStart: playStart,
      dur: s.dur,
      authStart: authStart,
      nat: nat
    });
    if (!Object.prototype.hasOwnProperty.call(table, s.name)) {
      table[s.name] = Math.round(authStart * 1000) / 1000;
    }
    playStart += s.dur;
    authStart += nat;
  }
  return {
    sections: sections,
    table: table,
    total: Math.round(playStart * 1000) / 1000,
    authoredTotal: Math.round(authStart * 1000) / 1000
  };
}
function ccWarp(d, t) {
  var ss = d.sections;
  if (ss.length === 0) return 0;
  var idx = ss.length - 1;
  for (var i = 0; i < ss.length; i++) {
    if (t < ss[i].playStart + ss[i].dur) {
      idx = i;
      break;
    }
  }
  var s = ss[idx];
  var local = Math.min(Math.max(t - s.playStart, 0), s.dur);
  var T = s.authStart + (s.dur > 0 ? local * (s.nat / s.dur) : 0);
  return Math.min(T, d.authoredTotal);
}
var CC_META = Object.assign(Object.create(null), {
  toString: 1,
  toLocaleString: 1,
  valueOf: 1,
  toJSON: 1,
  then: 1,
  constructor: 1,
  hasOwnProperty: 1,
  isPrototypeOf: 1,
  propertyIsEnumerable: 1,
  default: 1
});
function ccCueProxy(table, unknownRef) {
  if (typeof Proxy !== 'function') return table;
  return new Proxy(table, {
    get: function (target, prop) {
      if (typeof prop !== 'string' || prop in target) return target[prop];
      if (CC_META[prop] || prop.indexOf('@@') === 0) return Object.prototype[prop];
      unknownRef.current[prop] = true;
      return NaN;
    }
  });
}
function CcUnknownWatch(props) {
  var tl = useTimeline();
  React.useEffect(function () {
    var next = Object.keys(props.unknownRef.current).sort().join(', ');
    if (next !== props.badge) props.setBadge(next);
  }, [tl.time]);
  return null;
}
function CompositionClock(props) {
  var tl = useTimeline();
  var d = props.derived;
  var T = ccWarp(d, tl.time);
  var value = React.useMemo(function () {
    return {
      T: T,
      CUES: props.cues,
      time: tl.time,
      duration: tl.duration,
      authoredTotal: d.authoredTotal,
      playing: tl.playing || tl.extPlaying === true
    };
  }, [T, props.cues, tl.time, tl.duration, d, tl.playing, tl.extPlaying]);
  return /*#__PURE__*/React.createElement(CompositionContext.Provider, {
    value: value
  }, props.children);
}
function Shot(props) {
  var c = useComposition();
  var from = +props.from;
  var to = props.to == null ? Infinity : +props.to;
  var on = isFinite(from) && c.T >= from && c.T < to;
  return /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      inset: 0,
      visibility: on ? 'visible' : 'hidden'
    }
  }, props.children);
}
var CAPTION_FADE = 0.18;
function Captions(props) {
  var c = useComposition();
  var t = c.T;
  var items = (props.items || []).filter(function (it) {
    return it && isFinite(+it.at);
  }).sort(function (a, b) {
    return a.at - b.at;
  });
  var active = null;
  var end = Infinity;
  for (var i = 0; i < items.length; i++) {
    if (t < items[i].at) break;
    active = items[i];
    end = typeof active.until === 'number' && isFinite(active.until) ? active.until : i + 1 < items.length ? items[i + 1].at : Infinity;
  }
  if (!active || t >= end) return null;
  var o = Math.min(1, (t - active.at) / CAPTION_FADE);
  if (isFinite(end)) o = Math.min(o, (end - t) / CAPTION_FADE);
  o = Math.max(0, Math.min(1, o));
  return /*#__PURE__*/React.createElement("div", {
    "data-om-caption": true,
    style: Object.assign({
      position: 'absolute',
      left: '8%',
      right: '8%',
      bottom: '7%',
      textAlign: 'center',
      opacity: o,
      pointerEvents: 'none',
      font: '500 30px Inter, system-ui, sans-serif',
      color: '#f6f4ef',
      textShadow: '0 1px 14px rgba(0,0,0,0.45)'
    }, props.style)
  }, active.text);
}
function CompositionStage(props) {
  var width = +props.width || 1280;
  var height = +props.height || 720;
  var bg = props.bg || '#0b0b0e';
  var autoplay = props.autoplay == null ? true : String(props.autoplay) !== 'false';
  var loop = props.loop == null ? true : String(props.loop) !== 'false';
  var state = React.useState(props.scenes);
  var raw = state[0];
  var setRaw = state[1];
  var scenes = React.useMemo(function () {
    return ssParse(raw);
  }, [raw]);
  var pstate = React.useState(props.playback);
  var praw = pstate[0];
  var setPraw = pstate[1];
  var pb = React.useMemo(function () {
    return ppParse(praw);
  }, [praw]);
  var unknownRef = React.useRef({});
  var badgeState = React.useState('');
  var badge = badgeState[0];
  var setBadge = badgeState[1];
  var derived = React.useMemo(function () {
    unknownRef.current = {};
    return scenes ? ccDerive(scenes) : null;
  }, [scenes]);
  var cues = React.useMemo(function () {
    return derived ? ccCueProxy(derived.table, unknownRef) : null;
  }, [derived]);
  React.useEffect(function () {
    var next = Object.keys(unknownRef.current).sort().join(', ');
    if (next !== badge) setBadge(next);
  });
  if (!scenes) {
    return /*#__PURE__*/React.createElement("div", {
      style: {
        position: 'absolute',
        inset: 0,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: '#0b0b0e',
        color: '#c96442',
        font: '500 16px Inter, system-ui, sans-serif',
        textAlign: 'center'
      }
    }, "animations-v3: the scenes prop isn't a valid JSON scene list", /*#__PURE__*/React.createElement("br", null), "(expected '[", '{', "\"name\":\"\u2026\",\"dur\":N", '}', ", \u2026]')");
  }
  return /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement(Stage, {
    width: width,
    height: height,
    duration: derived.total,
    background: bg,
    autoplay: autoplay,
    loop: loop,
    playback: pb
  }, /*#__PURE__*/React.createElement(SceneSync, {
    raw: raw,
    onUpdate: setRaw
  }), typeof praw === 'string' && praw !== '' && /*#__PURE__*/React.createElement(PlaybackSync, {
    raw: praw,
    onUpdate: setPraw
  }), /*#__PURE__*/React.createElement(CompositionClock, {
    derived: derived,
    cues: cues
  }, props.children), /*#__PURE__*/React.createElement(CcUnknownWatch, {
    unknownRef: unknownRef,
    badge: badge,
    setBadge: setBadge
  })), badge !== '' &&
  /*#__PURE__*/
  // Sibling of Stage, outside the exportable <svg>: visible in the
  // preview (and its screenshots), never in the exported video.
  React.createElement("div", {
    "data-om-unknown-cues": true,
    style: {
      position: 'absolute',
      left: 12,
      bottom: 56,
      zIndex: 10,
      padding: '6px 10px',
      borderRadius: 6,
      background: 'rgba(0,0,0,0.72)',
      color: '#e8906a',
      font: '500 12px Inter, system-ui, sans-serif',
      pointerEvents: 'none'
    }
  }, "choreography references unknown section", badge.indexOf(',') >= 0 ? 's' : '', ": ", badge));
}

// Strokes as layers: paint multiplies, so stroke images stacked with
// mix-blend-mode:multiply over the paper reproduce the flat render.

var WC_PIXEL_CAP = 11000000;
function wcLayerOpts(props) {
  var w = +props.width || 900,
    h = +props.height || 1200;
  var askScale = +props.scale || 1;
  return {
    width: w,
    height: h,
    scale: Math.min(askScale, Math.sqrt(WC_PIXEL_CAP / (w * h))),
    seed: props.seed == null ? undefined : +props.seed,
    quality: props.quality == null ? undefined : +props.quality
  };
}
var wcWarned = {};
function wcWarnOnce(key, message, err) {
  if (wcWarned[key]) return;
  wcWarned[key] = true;
  console.warn(message, err);
}
function useWatercolorLayers(painting, opts) {
  var kit = window.WatercolorKit;
  if (typeof painting !== 'function' || !kit || typeof kit.layers !== 'function') return null;
  try {
    return kit.layers(painting, wcLayerOpts(opts || {}));
  } catch (e) {
    wcWarnOnce('layers:' + e, 'watercolor painting failed to build; rendering the fallback sheet', e);
    return null;
  }
}
var WatercolorSheetContext = React.createContext(null);
function WatercolorSheet(props) {
  var L = props.layers || null;
  var style = Object.assign({
    position: 'relative',
    display: 'block',
    width: '100%',
    aspectRatio: L ? L.width + ' / ' + L.height : '3 / 4',
    isolation: 'isolate',
    overflow: 'hidden'
  }, props.style);
  if (!L) {
    return /*#__PURE__*/React.createElement("div", {
      style: Object.assign(style, {
        background: '#f4f1e8',
        color: '#8a8270',
        font: '12px system-ui, sans-serif',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center'
      })
    }, "watercolor-kit.js not loaded (or the painting failed to build)");
  }
  return /*#__PURE__*/React.createElement(WatercolorSheetContext.Provider, {
    value: L
  }, /*#__PURE__*/React.createElement("div", {
    style: style,
    "data-om-watercolor-sheet": true
  }, /*#__PURE__*/React.createElement("img", {
    src: L.paper,
    alt: props.alt || '',
    style: {
      position: 'absolute',
      left: 0,
      top: 0,
      width: '100%',
      height: '100%',
      display: 'block'
    }
  }), props.children));
}
function WatercolorStroke(props) {
  var fromSheet = React.useContext(WatercolorSheetContext);
  var L = props.layers || fromSheet;
  if (!L) return null;
  var i = +props.index;
  if (!(i >= 0) || i >= L.count) return null;
  var at = props.at == null ? 1 : clamp(+props.at, 0, 1);
  if (!(at > 0)) return null;
  var box, src;
  try {
    box = L.box(i);
    src = box ? L.src(i, at) : null;
  } catch (e) {
    wcWarnOnce('stroke:' + i + ':' + e, 'watercolor stroke ' + i + ' failed to render; skipping it', e);
    return null;
  }
  if (!box || !src) return null;
  var style = Object.assign({
    position: 'absolute',
    display: 'block',
    left: box.x * 100 + '%',
    top: box.y * 100 + '%',
    width: box.w * 100 + '%',
    height: box.h * 100 + '%',
    mixBlendMode: L.kind(i) === 'reserve' ? 'normal' : 'multiply',
    pointerEvents: 'none'
  }, props.style);
  return /*#__PURE__*/React.createElement("img", {
    src: src,
    alt: "",
    "data-om-watercolor-stroke": i,
    "data-om-stroke-kind": L.kind(i),
    style: style
  });
}

// The default watercolor moment: the painting assembled from its strokes,
// each appearing in painting order (a pure function of T).
function WatercolorPainting(props) {
  var c = useComposition();
  var from = +props.from || 0;
  var to = props.to == null ? from + 6 : +props.to;
  var u = clamp((c.T - from) / Math.max(to - from, 0.001), 0, 1);
  var eased = Easing.easeInOutQuad(u);
  var L = useWatercolorLayers(props.painting, props);
  var tick = React.useState(0)[1];
  var warmed = React.useRef(null);
  React.useEffect(function () {
    if (!L || typeof L.warm !== 'function') return;
    var p = L.warm();
    if (warmed.current === p) return;
    var live = true;
    p.then(function () {
      warmed.current = p;
      if (live) tick(function (x) {
        return x + 1;
      });
    });
    return function () {
      live = false;
    };
  }, [L && L.paper, props.painting]);
  var strokes = [];
  if (L) {
    for (var i = 0; i < L.count; i++) {
      var sp = L.span(i);
      var at = clamp((eased - sp.from) / Math.max(sp.to - sp.from, 1e-6), 0, 1);
      if (at <= 0) break;
      strokes.push(/*#__PURE__*/React.createElement(WatercolorStroke, {
        key: i,
        layers: L,
        index: i,
        at: at
      }));
    }
  }
  return /*#__PURE__*/React.createElement(WatercolorSheet, {
    layers: L,
    style: props.style,
    alt: props.alt
  }, strokes);
}

// Paint-on watercolor reveal as a pure function of T — an <img> with a data:
// URL (the exporter serializes those as-is; a live canvas would export blank).
function WatercolorReveal(props) {
  var c = useComposition();
  var from = +props.from || 0;
  var to = props.to == null ? from + 6 : +props.to;
  var u = clamp((c.T - from) / Math.max(to - from, 0.001), 0, 1);
  var style = Object.assign({
    display: 'block',
    width: '100%',
    height: '100%',
    objectFit: 'contain'
  }, props.style);
  var frames = Array.isArray(props.frames) && props.frames.length ? props.frames : null;
  var steps = frames ? frames.length - 1 : Math.max(1, Math.round(+props.steps || 36));
  var i = Math.min(steps, Math.round(Easing.easeInOutQuad(u) * steps));
  var painting = typeof props.painting === 'function' ? props.painting : null;
  var kit = window.WatercolorKit;
  var w = +props.width || 900,
    h = +props.height || 1200;
  var askScale = +props.scale || Math.min(2, window.devicePixelRatio || 1);
  var opts = {
    width: w,
    height: h,
    scale: Math.min(askScale, Math.sqrt(11000000 / (w * h))),
    seed: props.seed == null ? undefined : +props.seed,
    steps: steps,
    type: props.format || 'image/jpeg',
    quality: props.quality == null ? 0.88 : +props.quality
  };
  var key = opts.width + 'x' + opts.height + '#' + opts.seed + '@' + opts.scale + '/' + steps + ':' + opts.type + '/' + opts.quality;
  var cache = React.useRef({
    fn: null,
    key: '',
    frames: {},
    baking: false
  }).current;
  var tick = React.useState(0)[1];
  if (cache.fn !== painting && String(cache.fn) !== String(painting) || cache.key !== key) {
    cache.key = key;
    cache.frames = {};
    cache.baking = false;
  }
  cache.fn = painting;
  React.useEffect(function () {
    if (frames || cache.baking || !painting || !kit || typeof kit.bake !== 'function') return;
    cache.baking = true;
    var target = cache.frames;
    try {
      kit.bake(painting, opts, function (n, _t, url) {
        target[n] = url;
      }).then(function (all) {
        if (cache.frames !== target) return;
        for (var n = 0; n < all.length; n++) target[n] = all[n];
        tick(function (x) {
          return x + 1;
        });
      }).catch(function () {
        /* failed bake: the guarded lazy path below still renders */
      });
    } catch (e) {
      /* oversized painting: the guarded lazy path below still renders */
    }
  });
  if (frames) return /*#__PURE__*/React.createElement("img", {
    src: frames[i],
    alt: props.alt || '',
    style: style
  });
  if (!kit || !painting) {
    return /*#__PURE__*/React.createElement("div", {
      style: Object.assign({
        width: '100%',
        height: '100%',
        background: '#f4f1e8',
        color: '#8a8270',
        font: '12px system-ui, sans-serif',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center'
      }, props.style)
    }, "watercolor-kit.js not loaded (or no painting function)");
  }
  if (!cache.frames[i]) {
    try {
      cache.frames[i] = kit.frame(painting, Object.assign({}, opts, {
        at: i / steps
      }));
    } catch (e) {
      return /*#__PURE__*/React.createElement("div", {
        style: Object.assign({
          width: '100%',
          height: '100%',
          background: '#f4f1e8',
          color: '#8a8270',
          font: '12px system-ui, sans-serif',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center'
        }, props.style)
      }, "painting too large to render (", String(e && e.message).slice(0, 80), ")");
    }
  }
  return /*#__PURE__*/React.createElement("img", {
    src: cache.frames[i],
    alt: props.alt || '',
    style: style
  });
}
Object.assign(window, {
  Easing,
  interpolate,
  animate,
  clamp,
  TimelineContext,
  useTime,
  useTimeline,
  Stage,
  PlaybackBar,
  CompositionStage,
  useComposition,
  Shot,
  Captions,
  WatercolorReveal,
  WatercolorPainting,
  WatercolorSheet,
  WatercolorStroke,
  useWatercolorLayers
});
})(window.React, {exports:{}}, {}, function(){ return {}; });

// ---- circles-scene.jsx (compiled with @babel/standalone 7.29.0, presets react+typescript; identical to the dc-runtime's load-time transform) ----
(function(React, module, exports, require){
// Circles explainer — one continuous composition.
// Everything renders all the time from T; nothing mounts per section.

const {
  useComposition,
  Shot,
  Captions,
  animate,
  interpolate,
  Easing,
  clamp,
  CompositionStage
} = window;
const CX = 660,
  CY = 540;
const RINGS = [{
  name: 'Just me',
  r: 96,
  color: '#22C0E8'
}, {
  name: 'Family',
  r: 186,
  color: '#419CA0'
}, {
  name: 'Neighbours',
  r: 282,
  color: '#7A6FD6'
}, {
  name: 'Work',
  r: 384,
  color: '#C96A38'
}, {
  name: 'Everyone',
  r: 492,
  color: '#B08A3E'
}];
const MOTION = {
  enter: (start, end) => animate({
    from: 0,
    to: 1,
    start,
    end,
    ease: Easing.easeOutCubic
  }),
  draw: (start, end) => animate({
    from: 0,
    to: 1,
    start,
    end,
    ease: Easing.easeInOutCubic
  }),
  pop: (start, end) => animate({
    from: 0,
    to: 1,
    start,
    end,
    ease: Easing.easeOutBack
  })
};
function track(T, points, ease) {
  const e = ease || Easing.easeInOutCubic;
  if (T <= points[0][0]) return points[0][1];
  for (let i = 0; i < points.length - 1; i++) {
    const [t0, v0] = points[i],
      [t1, v1] = points[i + 1];
    if (T <= t1) {
      if (t1 <= t0) return v1;
      return animate({
        from: v0,
        to: v1,
        start: t0,
        end: t1,
        ease: e
      })(T);
    }
  }
  return points[points.length - 1][1];
}
const polar = (r, deg) => [CX + r * Math.cos(deg * Math.PI / 180), CY + r * Math.sin(deg * Math.PI / 180)];
const CONTACTS = [{
  ring: 1,
  a: 100,
  label: 'Dad'
}, {
  ring: 1,
  a: 160,
  label: 'Mira'
}, {
  ring: 1,
  a: -150,
  label: 'Sam'
}, {
  ring: 2,
  a: 50,
  label: 'Clinic'
}, {
  ring: 2,
  a: 96,
  label: 'Class 4B'
}, {
  ring: 2,
  a: 142,
  label: 'A. Kaur'
}, {
  ring: 2,
  a: -170,
  label: 'Watch'
}, {
  ring: 2,
  a: -122,
  label: 'J. Adeyemi'
}, {
  ring: 3,
  a: -128,
  label: 'Datum Labs'
}, {
  ring: 3,
  a: 74,
  label: 'Guild'
}, {
  ring: 4,
  a: -95,
  label: 'ciris-root'
}, {
  ring: 4,
  a: 35,
  label: 'commons-safety'
}, {
  ring: 4,
  a: 160,
  label: 'peers'
}];
const MONO = "'Geist Mono', ui-monospace, monospace";
const SANS = "Geist, ui-sans-serif, system-ui, sans-serif";
function Panel(props) {
  return /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: 1180,
      top: props.top,
      width: 660,
      background: '#151B24',
      border: '1px solid rgba(255,255,255,0.1)',
      borderRadius: 18,
      padding: '30px 34px',
      display: 'flex',
      flexDirection: 'column',
      gap: 18,
      opacity: props.o,
      transform: 'translateY(' + (1 - props.o) * 18 + 'px)'
    }
  }, props.children);
}
function Eyebrow(props) {
  return /*#__PURE__*/React.createElement("div", {
    style: {
      font: '500 22px ' + MONO,
      letterSpacing: '0.12em',
      textTransform: 'uppercase',
      color: props.color
    }
  }, props.children);
}
function Head(props) {
  return /*#__PURE__*/React.createElement("div", {
    style: {
      font: '600 34px ' + SANS,
      letterSpacing: '-0.015em',
      lineHeight: 1.2
    }
  }, props.children);
}
function Body(props) {
  return /*#__PURE__*/React.createElement("div", {
    style: {
      font: '400 23px ' + SANS,
      lineHeight: 1.5,
      color: '#9AA3AF'
    }
  }, props.children);
}

// a small photo card, drawn in CSS only
function Photo(props) {
  return /*#__PURE__*/React.createElement("div", {
    style: {
      width: 132,
      height: 104,
      borderRadius: 12,
      overflow: 'hidden',
      background: '#2A3A44',
      border: '2px solid #F4F5F7',
      boxShadow: '0 12px 40px rgba(0,0,0,0.5)',
      position: 'relative',
      filter: props.blur ? 'blur(' + props.blur + 'px)' : 'none'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      inset: 0,
      background: 'linear-gradient(180deg,#5B7F93 0%,#8AA9AE 58%,#C7B893 100%)'
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: 16,
      top: 14,
      width: 26,
      height: 26,
      borderRadius: '50%',
      background: '#F7E6B8'
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: -6,
      bottom: -18,
      width: 96,
      height: 62,
      borderRadius: '50%',
      background: '#4B6B52'
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      right: -14,
      bottom: -24,
      width: 104,
      height: 70,
      borderRadius: '50%',
      background: '#3E5C46'
    }
  }));
}
function Piece() {
  const {
    T,
    CUES,
    authoredTotal
  } = useComposition();

  // cue lookup that tolerates name normalisation and never yields NaN
  const cue = (name, fallback) => {
    const keys = Object.keys(CUES || {});
    const want = String(name).toLowerCase().replace(/[^a-z0-9]/g, '');
    for (const k of keys) {
      if (String(k).toLowerCase().replace(/[^a-z0-9]/g, '') === want) {
        const v = CUES[k];
        if (Number.isFinite(v)) return v;
      }
    }
    return fallback;
  };
  const C = {
    Today: cue('Today', 0),
    Copies: cue('Copies', 14),
    NoTakeBack: cue('NoTakeBack', 28),
    Different: cue('Different', 40),
    Open: cue('Open', 54),
    Contacts: cue('Contacts', 10),
    Born: cue('Born', 22),
    Envelope: cue('Envelope', 30),
    Photo: cue('Photo', 48),
    PhotoOut: cue('PhotoOut', 60),
    Move: cue('Move', 76),
    Content: cue('Content', 92),
    PhotoLabel: cue('PhotoLabel', 106),
    Object: cue('Object', 118),
    TakeBack: cue('TakeBack', 134),
    PhotoGone: cue('PhotoGone', 146),
    Close: cue('Close', 158)
  };
  const total = Number.isFinite(authoredTotal) ? authoredTotal : C.Close + 12;
  const fin = (v, f) => Number.isFinite(v) ? v : f;

  // ── the form's journey ──────────────────────────────────────────────
  const famPos = polar(RINGS[1].r, 20);
  const comPos = polar(RINGS[2].r, 12);
  const px = track(T, [[C.Born, CX], [C.Move + 3.2, CX], [C.Move + 6.0, famPos[0]], [C.Move + 9.2, famPos[0]], [C.Move + 12.4, comPos[0]], [C.TakeBack + 3.6, comPos[0]], [C.PhotoGone + 9.2, CX]]);
  const py = track(T, [[C.Born, CY], [C.Move + 3.2, CY], [C.Move + 6.0, famPos[1]], [C.Move + 9.2, famPos[1]], [C.Move + 12.4, comPos[1]], [C.TakeBack + 3.6, comPos[1]], [C.PhotoGone + 9.2, CY]]);
  const docIn = fin(MOTION.pop(C.Born + 0.4, C.Born + 2.4)(T), 0);
  const docOut = 1 - fin(MOTION.draw(C.Close + 0.2, C.Close + 2.2)(T), 0);
  // the form steps off while the photo's story runs (Photo → Move): the r=96 inner
  // circle cannot hold both cards, so only one is ever on stage at the centre.
  const docShow = clamp(1 - fin(MOTION.draw(C.Photo - 2.0, C.Photo - 0.4)(T), 0) + fin(MOTION.draw(C.Move - 2.0, C.Move - 0.4)(T), 0), 0, 1);
  const docScale = fin(track(T, [[C.Born, 1], [C.Move + 12.4, 1], [C.Object, 0.86]], Easing.easeOutCubic), 1);
  const docX = fin(px, CX),
    docY = fin(py, CY);

  // ── the photo's journey (its own story, told in the gaps) ───────────
  const pFam = polar(RINGS[1].r, -14);
  const pCom = polar(RINGS[2].r, -48);
  const phx = track(T, [[C.Photo, CX - 26], [C.Photo + 6, CX - 26], [C.PhotoOut + 3.0, pFam[0]], [C.PhotoOut + 7.0, pFam[0]], [C.PhotoOut + 10.5, pCom[0]], [C.PhotoGone + 4.0, pCom[0]], [C.PhotoGone + 7.5, CX - 26]]);
  const phy = track(T, [[C.Photo, CY + 44], [C.Photo + 6, CY + 44], [C.PhotoOut + 3.0, pFam[1]], [C.PhotoOut + 7.0, pFam[1]], [C.PhotoOut + 10.5, pCom[1]], [C.PhotoGone + 4.0, pCom[1]], [C.PhotoGone + 7.5, CY + 44]]);
  const photoIn = fin(MOTION.pop(C.Photo + 0.4, C.Photo + 2.4)(T), 0);
  const photoX = fin(phx, CX - 26),
    photoY = fin(phy, CY + 44);
  const photoBlur = 9 * MOTION.draw(C.PhotoLabel + 3.0, C.PhotoLabel + 5.5)(T) * (1 - MOTION.draw(C.Object + 1.0, C.Object + 3.0)(T));
  const photoOut = 1 - MOTION.draw(C.PhotoGone + 6.5, C.PhotoGone + 9.0)(T);

  // ── the intro: how it works today ──────────────────────────────────
  const boxIn = MOTION.enter(C.Today + 0.6, C.Today + 2.6)(T);
  const introOut = 1 - fin(MOTION.draw(C.Different - 2.4, C.Different - 0.4)(T), 0);
  const signetIn = MOTION.enter(C.Different + 0.8, C.Different + 3.4)(T);
  const signetOut = 1 - fin(MOTION.draw(C.Open - 2.0, C.Open - 0.3)(T), 0);
  const upIn = MOTION.enter(C.Today + 3.4, C.Today + 6.0)(T);
  const copyOut = i => MOTION.enter(C.Copies + 1.6 + i * 2.4, C.Copies + 4.4 + i * 2.4)(T);
  const strikeIn = MOTION.draw(C.NoTakeBack + 3.0, C.NoTakeBack + 6.0)(T);
  const ringsIn = i => MOTION.enter(C.Open + 0.7 + i * 0.85, C.Open + 3.0 + i * 0.85)(T);
  const contactsIn = i => MOTION.pop(C.Contacts + 0.6 + i * 0.34, C.Contacts + 2.6 + i * 0.34)(T);
  const objFill = MOTION.draw(C.Object + 2.0, C.Object + 6.0)(T);
  const stewFill = MOTION.draw(C.Object + 6.4, C.Object + 9.6)(T);
  const escFill = MOTION.draw(C.Object + 10.0, C.Object + 12.8)(T);
  const closeIn = MOTION.enter(C.Close + 0.4, C.Close + 2.8)(T);
  const dim = 1 - 0.55 * MOTION.draw(C.Close + 0.4, C.Close + 3.2)(T);
  return /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      inset: 0,
      fontFamily: SANS,
      color: '#F4F5F7',
      overflow: 'hidden'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: CX - 250,
      top: CY - 170,
      width: 500,
      height: 340,
      borderRadius: 22,
      border: '2px solid #4A5260',
      background: '#161B22',
      opacity: boxIn * introOut,
      transform: 'scale(' + (0.94 + 0.06 * boxIn) + ')'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: 0,
      right: 0,
      top: 26,
      textAlign: 'center',
      font: '600 30px ' + SANS,
      color: '#8A93A0'
    }
  }, "big tech datacenter"), /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: '50%',
      top: 150,
      transform: 'translate(-50%,-50%) rotate(-4deg) scale(' + (0.6 + 0.4 * upIn) + ')',
      opacity: upIn
    }
  }, /*#__PURE__*/React.createElement(Photo, null), /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: '50%',
      top: 116,
      transform: 'translateX(-50%)',
      font: '500 22px ' + MONO,
      color: '#C6CCD4',
      whiteSpace: 'nowrap'
    }
  }, "your photo")), /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: 40,
      right: 40,
      bottom: 34,
      textAlign: 'center',
      font: '400 23px ' + SANS,
      color: '#6B7280',
      opacity: MOTION.enter(C.Today + 6.5, C.Today + 9.0)(T)
    }
  }, "you keep the memory. they keep the copy."), /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: '50%',
      top: '50%',
      width: 420,
      height: 6,
      transform: 'translate(-50%,-50%) rotate(-10deg) scaleX(' + strikeIn + ')',
      background: '#F87171',
      borderRadius: 999,
      opacity: strikeIn * introOut
    }
  })), [['Ads', -190], ['AI training', 0], ['Partners', 190]].map((c, i) => {
    const o = copyOut(i) * introOut;
    return /*#__PURE__*/React.createElement("div", {
      key: c[0],
      style: {
        position: 'absolute',
        left: CX + c[1],
        top: CY + 230 + (1 - o) * -60,
        transform: 'translate(-50%,-50%)',
        opacity: o,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        gap: 10
      }
    }, /*#__PURE__*/React.createElement("div", {
      style: {
        width: 66,
        height: 52,
        borderRadius: 8,
        background: '#2A3A44',
        border: '2px solid #6B7280',
        opacity: 0.9
      }
    }), /*#__PURE__*/React.createElement("span", {
      style: {
        font: '500 24px ' + MONO,
        color: '#9AA3AF',
        whiteSpace: 'nowrap'
      }
    }, c[0]));
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      inset: 0,
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'center',
      justifyContent: 'center',
      gap: 34,
      opacity: signetIn * signetOut,
      pointerEvents: 'none'
    }
  }, /*#__PURE__*/React.createElement("img", {
    src: "circles/ciris-signet.svg",
    alt: "",
    style: {
      width: 240,
      height: 'auto',
      transform: 'scale(' + (0.86 + 0.14 * signetIn) + ') rotate(' + (1 - signetIn) * -14 + 'deg)',
      filter: 'brightness(0) invert(1)'
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      font: '600 66px ' + SANS,
      letterSpacing: '-0.03em',
      textAlign: 'center',
      lineHeight: 1.12
    }
  }, "CIRIS is built open,", /*#__PURE__*/React.createElement("br", null), "free, and different."), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'center',
      gap: 6,
      background: '#151B24',
      border: '1px solid rgba(34,192,232,0.3)',
      borderRadius: 12,
      padding: '16px 26px',
      opacity: MOTION.enter(C.Different + 4.5, C.Different + 6.5)(T)
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      font: '500 28px ' + MONO,
      color: '#22C0E8'
    }
  }, "ciris.ai"), /*#__PURE__*/React.createElement("span", {
    style: {
      font: '400 21px ' + SANS,
      color: '#9AA3AF'
    }
  }, "the whole thing, in the open"))), RINGS.map((ring, i) => {
    const o = ringsIn(i);
    return /*#__PURE__*/React.createElement("div", {
      key: ring.name
    }, /*#__PURE__*/React.createElement("div", {
      style: {
        position: 'absolute',
        left: CX - ring.r,
        top: CY - ring.r,
        width: ring.r * 2,
        height: ring.r * 2,
        borderRadius: '50%',
        border: '1.5px solid ' + ring.color,
        opacity: o * 0.5 * dim,
        transform: 'scale(' + (0.88 + 0.12 * o) + ')',
        background: i === 0 ? ring.color + '14' : 'transparent'
      }
    }), /*#__PURE__*/React.createElement("div", {
      style: {
        position: 'absolute',
        left: CX - 4,
        top: CY - ring.r - 42,
        transform: 'translateX(-50%)',
        opacity: o * dim,
        font: '500 27px ' + MONO,
        letterSpacing: '0.04em',
        color: ring.color,
        whiteSpace: 'nowrap'
      }
    }, ring.name));
  }), CONTACTS.map((c, i) => {
    const o = contactsIn(i);
    const [x, y] = polar(RINGS[c.ring].r, c.a);
    const color = RINGS[c.ring].color;
    const isObjector = c.label === 'A. Kaur';
    const objecting = isObjector && T > C.Object + 1.6 && T < C.TakeBack ? 0.5 + 0.5 * Math.sin((T - C.Object) * 3.2) : 0;
    return /*#__PURE__*/React.createElement("div", {
      key: c.label,
      style: {
        position: 'absolute',
        left: x,
        top: y,
        transform: 'translate(-50%,-50%)',
        opacity: o * dim
      }
    }, /*#__PURE__*/React.createElement("div", {
      style: {
        width: 30,
        height: 30,
        borderRadius: 10,
        background: color + '33',
        border: '1.5px solid ' + (objecting ? '#C96A38' : color),
        boxShadow: objecting ? '0 0 0 ' + (4 + 8 * objecting) + 'px rgba(201,106,56,' + 0.22 * objecting + ')' : 'none',
        transform: 'scale(' + (0.7 + 0.3 * o) + ')'
      }
    }), /*#__PURE__*/React.createElement("div", {
      style: {
        position: 'absolute',
        left: '50%',
        top: 38,
        transform: 'translateX(-50%)',
        font: '500 25px ' + MONO,
        color: '#C6CCD4',
        whiteSpace: 'nowrap',
        textShadow: '0 2px 12px #0D1117, 0 0 8px #0D1117'
      }
    }, c.label));
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: photoX,
      top: photoY,
      transform: 'translate(-50%,-50%) scale(' + (0.6 + 0.4 * photoIn) * (0.85 + 0.15 * photoOut) + ') rotate(-4deg)',
      opacity: photoIn * photoOut
    }
  }, /*#__PURE__*/React.createElement(Photo, {
    blur: photoBlur
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: '50%',
      top: 116,
      transform: 'translateX(-50%)',
      font: '500 22px ' + MONO,
      color: '#C6CCD4',
      whiteSpace: 'nowrap',
      textShadow: '0 2px 12px #0D1117, 0 0 8px #0D1117'
    }
  }, "beach.jpg")), /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: docX,
      top: docY,
      transform: 'translate(-50%,-50%) scale(' + docScale * (0.6 + 0.4 * docIn) * (0.82 + 0.18 * docOut) * (0.9 + 0.1 * docShow) + ')',
      opacity: docIn * docOut * docShow,
      width: 124,
      height: 152,
      borderRadius: 13,
      background: '#F4F5F7',
      border: '1px solid rgba(255,255,255,0.4)',
      boxShadow: '0 12px 40px rgba(0,0,0,0.55)',
      padding: '14px 12px',
      display: 'flex',
      flexDirection: 'column',
      gap: 7
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      height: 7,
      width: '74%',
      background: '#0D1117',
      opacity: 0.8,
      borderRadius: 2
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      height: 5,
      width: '100%',
      background: '#0D1117',
      opacity: 0.22,
      borderRadius: 2
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      height: 5,
      width: '92%',
      background: '#0D1117',
      opacity: 0.22,
      borderRadius: 2
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      height: 5,
      width: '96%',
      background: '#0D1117',
      opacity: 0.22,
      borderRadius: 2
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      height: 5,
      width: '60%',
      background: '#0D1117',
      opacity: 0.22,
      borderRadius: 2
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 'auto',
      font: '500 14px ' + MONO,
      color: '#4A5260'
    }
  }, "form.pdf")), /*#__PURE__*/React.createElement(Shot, {
    from: 0,
    to: C.Copies
  }, /*#__PURE__*/React.createElement(Panel, {
    top: 360,
    o: boxIn
  }, /*#__PURE__*/React.createElement(Eyebrow, {
    color: "#8A93A0"
  }, "how it works today"), /*#__PURE__*/React.createElement(Head, null, "You post a photo. It goes to a big tech datacenter."), /*#__PURE__*/React.createElement(Body, null, "Not yours. Theirs."))), /*#__PURE__*/React.createElement(Shot, {
    from: C.Copies,
    to: C.NoTakeBack
  }, /*#__PURE__*/React.createElement(Panel, {
    top: 340,
    o: MOTION.enter(C.Copies + 0.3, C.Copies + 2.0)(T)
  }, /*#__PURE__*/React.createElement(Eyebrow, {
    color: "#8A93A0"
  }, "then it spreads"), /*#__PURE__*/React.createElement(Head, null, "Copies go out. Nobody asks you first."), /*#__PURE__*/React.createElement(Body, null, "A form said yes once, years ago."))), /*#__PURE__*/React.createElement(Shot, {
    from: C.NoTakeBack,
    to: C.Different
  }, /*#__PURE__*/React.createElement(Panel, {
    top: 340,
    o: MOTION.enter(C.NoTakeBack + 0.3, C.NoTakeBack + 2.0)(T)
  }, /*#__PURE__*/React.createElement(Eyebrow, {
    color: "#F87171"
  }, "and you cannot undo it"), /*#__PURE__*/React.createElement(Head, null, "Delete removes it from your screen."), /*#__PURE__*/React.createElement(Body, null, "The copies stay. You cannot see them, ask about them, or call them back."), /*#__PURE__*/React.createElement(Body, null, "CIRIS starts somewhere else."))), /*#__PURE__*/React.createElement(Shot, {
    from: C.Different - 0.3,
    to: C.Open
  }, /*#__PURE__*/React.createElement(Panel, {
    top: 760,
    o: MOTION.enter(C.Different + 6.0, C.Different + 8.0)(T)
  }, /*#__PURE__*/React.createElement(Body, null, "No owner. No ads. Nothing to sell, because there is nothing to collect."))), /*#__PURE__*/React.createElement(Shot, {
    from: C.Envelope - 0.3,
    to: C.Photo
  }, /*#__PURE__*/React.createElement(Panel, {
    top: 260,
    o: MOTION.enter(C.Envelope, C.Envelope + 1.6)(T)
  }, /*#__PURE__*/React.createElement(Eyebrow, {
    color: "#22C0E8"
  }, "what travels with it"), /*#__PURE__*/React.createElement(Body, null, "Five facts go with it, every time."), [['Who it is about', 'you and Mira'], ['Who sent it', 'your key'], ['Who can see it', 'just me'], ['What it is', 'a document'], ['The rule it follows', 'family only']].map((row, i) => {
    const o = MOTION.enter(C.Envelope + 2.4 + i * 2.6, C.Envelope + 4.4 + i * 2.6)(T);
    const last = i === 4;
    return /*#__PURE__*/React.createElement("div", {
      key: row[0],
      style: {
        opacity: o,
        transform: 'translateX(' + (1 - o) * 14 + 'px)',
        display: 'flex',
        flexDirection: 'column',
        gap: 4,
        borderBottom: '1px solid rgba(255,255,255,0.07)',
        paddingBottom: 12
      }
    }, /*#__PURE__*/React.createElement("div", {
      style: {
        display: 'flex',
        gap: 14,
        alignItems: 'baseline',
        flexWrap: 'wrap'
      }
    }, /*#__PURE__*/React.createElement("span", {
      style: {
        font: '400 24px ' + SANS,
        color: last ? '#22C0E8' : '#8A93A0'
      }
    }, row[0]), /*#__PURE__*/React.createElement("code", {
      style: {
        font: '500 24px ' + MONO,
        color: '#F4F5F7'
      }
    }, row[1])));
  }))), /*#__PURE__*/React.createElement(Shot, {
    from: C.Photo,
    to: C.PhotoOut
  }, /*#__PURE__*/React.createElement(Panel, {
    top: 340,
    o: MOTION.enter(C.Photo + 0.3, C.Photo + 1.8)(T)
  }, /*#__PURE__*/React.createElement(Eyebrow, {
    color: "#22C0E8"
  }, "a picture, from the start"), /*#__PURE__*/React.createElement(Head, null, "You take a photo."), /*#__PURE__*/React.createElement(Body, null, "It starts with you. Nobody else has it."))), /*#__PURE__*/React.createElement(Shot, {
    from: C.PhotoOut,
    to: C.Move
  }, /*#__PURE__*/React.createElement(Panel, {
    top: 330,
    o: MOTION.enter(C.PhotoOut + 0.3, C.PhotoOut + 1.8)(T)
  }, /*#__PURE__*/React.createElement(Eyebrow, {
    color: "#419CA0"
  }, "you decide to share it"), /*#__PURE__*/React.createElement(Head, null, T < C.PhotoOut + 7.0 ? 'To your family' : 'Then to the neighbours'), (T < C.PhotoOut + 7.0 ? ['Three people can see it.', 'Nobody outside can even tell it exists.'] : ['Thirty-one people can see it.', 'Some of them you never picked yourself.']).map((f, i) => {
    const base = T < C.PhotoOut + 7.0 ? C.PhotoOut + 2.0 : C.PhotoOut + 8.4;
    const o = MOTION.enter(base + i * 1.4, base + 3.0 + i * 1.4)(T);
    return /*#__PURE__*/React.createElement("div", {
      key: i,
      style: {
        opacity: o,
        display: 'flex',
        gap: 14,
        alignItems: 'flex-start',
        font: '400 25px ' + SANS,
        lineHeight: 1.45,
        color: '#9AA3AF'
      }
    }, /*#__PURE__*/React.createElement("span", {
      style: {
        color: '#419CA0'
      }
    }, "\u2192"), /*#__PURE__*/React.createElement("span", null, f));
  }), /*#__PURE__*/React.createElement(Body, null, "One tap. One clear question."))), /*#__PURE__*/React.createElement(Shot, {
    from: C.Move,
    to: C.Content
  }, /*#__PURE__*/React.createElement(Panel, {
    top: 350,
    o: MOTION.enter(C.Move + 0.3, C.Move + 1.8)(T)
  }, /*#__PURE__*/React.createElement(Eyebrow, {
    color: "#FBBF24"
  }, "the form goes out too"), /*#__PURE__*/React.createElement(Head, null, T < C.Move + 9.2 ? 'Out to the family' : 'Out to the neighbours'), /*#__PURE__*/React.createElement(Body, null, "Going out asks. Coming back never does."), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 12,
      marginTop: 6
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      background: '#419CA0',
      color: '#08131A',
      font: '600 23px ' + SANS,
      borderRadius: 10,
      padding: '14px 24px'
    }
  }, "Move it"), /*#__PURE__*/React.createElement("div", {
    style: {
      border: '1px solid rgba(255,255,255,0.16)',
      color: '#C6CCD4',
      font: '400 23px ' + SANS,
      borderRadius: 10,
      padding: '14px 24px'
    }
  }, "Leave it")))), /*#__PURE__*/React.createElement(Shot, {
    from: C.Content,
    to: C.PhotoLabel
  }, /*#__PURE__*/React.createElement(Panel, {
    top: 280,
    o: MOTION.enter(C.Content + 0.3, C.Content + 1.8)(T)
  }, /*#__PURE__*/React.createElement(Eyebrow, {
    color: "#7A6FD6"
  }, "files come first"), /*#__PURE__*/React.createElement(Body, null, "Every circle has a Files tab."), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 10,
      flexWrap: 'wrap',
      alignItems: 'center'
    }
  }, ['my rules', 'two helpers', 'newest first'].map((f, i) => /*#__PURE__*/React.createElement("span", {
    key: f,
    style: {
      font: '500 21px ' + MONO,
      borderRadius: 999,
      padding: '9px 18px',
      whiteSpace: 'nowrap',
      opacity: MOTION.enter(C.Content + 2.0 + i * 1.1, C.Content + 3.6 + i * 1.1)(T),
      background: i < 2 ? '#7A6FD6' : 'transparent',
      color: i < 2 ? '#0D1117' : '#9AA3AF',
      border: '1px solid ' + (i < 2 ? '#7A6FD6' : 'rgba(255,255,255,0.16)')
    }
  }, f))), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'grid',
      gridTemplateColumns: '1fr 1fr 1fr',
      gap: 12
    }
  }, ['beach.jpg', 'form.pdf', 'walk.mp4', 'notes.docx', 'budget.pdf', 'plan.glb'].map((n, i) => {
    const o = MOTION.pop(C.Content + 5.4 + i * 0.8, C.Content + 7.2 + i * 0.8)(T);
    return /*#__PURE__*/React.createElement("div", {
      key: n,
      style: {
        opacity: o,
        transform: 'scale(' + (0.9 + 0.1 * o) + ')',
        background: '#0F141B',
        border: '1px solid rgba(255,255,255,0.08)',
        borderRadius: 10,
        overflow: 'hidden'
      }
    }, /*#__PURE__*/React.createElement("div", {
      style: {
        height: 72,
        background: '#7A6FD629'
      }
    }), /*#__PURE__*/React.createElement("div", {
      style: {
        padding: '8px 10px',
        display: 'flex',
        flexDirection: 'column',
        gap: 3
      }
    }, /*#__PURE__*/React.createElement("span", {
      style: {
        font: '500 21px ' + SANS
      }
    }, n), /*#__PURE__*/React.createElement("span", {
      style: {
        font: '400 18px ' + MONO,
        color: '#8A93A0'
      }
    }, "on 3 devices")));
  })), /*#__PURE__*/React.createElement(Body, null, "You choose the order, and whose opinions count."))), /*#__PURE__*/React.createElement(Shot, {
    from: C.PhotoLabel,
    to: C.Object
  }, /*#__PURE__*/React.createElement(Panel, {
    top: 340,
    o: MOTION.enter(C.PhotoLabel + 0.3, C.PhotoLabel + 1.8)(T)
  }, /*#__PURE__*/React.createElement(Eyebrow, {
    color: "#B08A3E"
  }, "someone tags the photo"), /*#__PURE__*/React.createElement(Head, null, "A neighbour adds a note."), /*#__PURE__*/React.createElement(Body, null, "They cannot delete it. A note is an opinion, not an order."), /*#__PURE__*/React.createElement(Body, null, "Your rules say blur it. So it blurs."))), /*#__PURE__*/React.createElement(Shot, {
    from: C.Object,
    to: C.TakeBack
  }, /*#__PURE__*/React.createElement(Panel, {
    top: 300,
    o: MOTION.enter(C.Object + 0.3, C.Object + 1.8)(T)
  }, /*#__PURE__*/React.createElement(Eyebrow, {
    color: "#C96A38"
  }, "how the group steps in"), /*#__PURE__*/React.createElement(Head, null, "One person can pull the brake. It takes a group to let go of it."), [['Anyone speaks up', 'one person is enough — always, in every group', objFill, '#C96A38'], ['The named helpers answer', 'or they do not answer at all', stewFill, '#FBBF24'], ['If nobody answers', 'the group decides instead, and never fewer than three', escFill, '#4ADE80']].map(row => /*#__PURE__*/React.createElement("div", {
    key: row[0],
    style: {
      display: 'flex',
      flexDirection: 'column',
      gap: 8
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 12,
      alignItems: 'baseline',
      flexWrap: 'wrap'
    }
  }, /*#__PURE__*/React.createElement("b", {
    style: {
      font: '600 25px ' + SANS,
      color: row[3]
    }
  }, row[0]), /*#__PURE__*/React.createElement("span", {
    style: {
      font: '400 22px ' + SANS,
      color: '#9AA3AF'
    }
  }, row[1])), /*#__PURE__*/React.createElement("div", {
    style: {
      height: 12,
      borderRadius: 999,
      background: 'rgba(255,255,255,0.07)',
      overflow: 'hidden'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      height: '100%',
      width: row[2] * 100 + '%',
      background: row[3],
      borderRadius: 999
    }
  })))), /*#__PURE__*/React.createElement(Body, null, "So it never waits for someone in charge."))), /*#__PURE__*/React.createElement(Shot, {
    from: C.TakeBack,
    to: C.PhotoGone
  }, /*#__PURE__*/React.createElement(Panel, {
    top: 360,
    o: MOTION.enter(C.TakeBack + 0.3, C.TakeBack + 1.8)(T)
  }, /*#__PURE__*/React.createElement(Eyebrow, {
    color: "#4ADE80"
  }, "taking it back"), /*#__PURE__*/React.createElement(Head, null, "Mira is in the photo."), /*#__PURE__*/React.createElement(Body, null, "So Mira can take it back. Not just the person who shared it."))), /*#__PURE__*/React.createElement(Shot, {
    from: C.PhotoGone,
    to: C.Close
  }, /*#__PURE__*/React.createElement(Panel, {
    top: 340,
    o: MOTION.enter(C.PhotoGone + 0.3, C.PhotoGone + 1.8)(T)
  }, /*#__PURE__*/React.createElement(Eyebrow, {
    color: "#22C0E8"
  }, "the photo comes home"), /*#__PURE__*/React.createElement(Head, null, "Back to just me."), /*#__PURE__*/React.createElement(Body, null, "The photo carries its receipt everywhere it goes. Apps that play fair read it, and honour it."), /*#__PURE__*/React.createElement(Body, null, "What we cannot stop is a screenshot, or someone who breaks the rules on purpose."), /*#__PURE__*/React.createElement(Body, null, "So this is not a lock. It is a clear, signed request that good neighbours keep."))), /*#__PURE__*/React.createElement(Shot, {
    from: C.Close - 0.2,
    to: total
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: 0,
      right: 0,
      top: 340,
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'center',
      gap: 24,
      opacity: closeIn
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      font: '600 64px ' + SANS,
      letterSpacing: '-0.03em',
      textAlign: 'center',
      lineHeight: 1.12
    }
  }, "Starts close to you.", /*#__PURE__*/React.createElement("br", null), "Moves out only when you say so.", /*#__PURE__*/React.createElement("br", null), "Comes back if anyone in it asks."), /*#__PURE__*/React.createElement("div", {
    style: {
      font: '400 26px ' + SANS,
      color: '#9AA3AF',
      textAlign: 'center',
      maxWidth: 940,
      lineHeight: 1.5
    }
  }, "Privacy is not hiding things. It is things moving the way people expect."), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 26,
      display: 'flex',
      gap: 14,
      flexWrap: 'wrap',
      justifyContent: 'center',
      opacity: MOTION.enter(C.Close + 2.4, C.Close + 4.4)(T)
    }
  }, [['ciris.ai/contextual-integrity', 'the five facts, in full'], ['ciris.ai/safety', 'the stop button nobody can talk past']].map(l => /*#__PURE__*/React.createElement("div", {
    key: l[0],
    style: {
      background: '#151B24',
      border: '1px solid rgba(34,192,232,0.3)',
      borderRadius: 12,
      padding: '16px 22px',
      display: 'flex',
      flexDirection: 'column',
      gap: 5,
      textAlign: 'left'
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      font: '500 26px ' + MONO,
      color: '#22C0E8'
    }
  }, l[0]), /*#__PURE__*/React.createElement("span", {
    style: {
      font: '400 21px ' + SANS,
      color: '#9AA3AF'
    }
  }, l[1])))))), /*#__PURE__*/React.createElement(Captions, {
    items: [{
      at: 0,
      text: 'Today, what you make goes to a big tech datacenter. Not yours.'
    }, {
      at: C.Copies,
      text: 'Copies go out to people you never picked.'
    }, {
      at: C.NoTakeBack,
      text: 'Delete hides it from you. The copies stay.'
    }, {
      at: C.Different,
      text: 'CIRIS is built open, free, and different.'
    }, {
      at: C.Open,
      text: 'Five circles. Closest to you first.'
    }, {
      at: C.Contacts,
      text: 'A circle is just the people in it.'
    }, {
      at: C.Born,
      text: 'New things start in the middle, with you.'
    }, {
      at: C.Envelope,
      text: 'Five facts travel with everything.'
    }, {
      at: C.Photo,
      text: 'You take a photo. It starts with you.'
    }, {
      at: C.PhotoOut,
      text: 'Sharing is a step you choose, one circle at a time.'
    }, {
      at: C.Move,
      text: 'Going out asks. Coming back never does.'
    }, {
      at: C.Content,
      text: 'Files come first. Every circle has them.'
    }, {
      at: C.PhotoLabel,
      text: 'Tags are opinions. Your rules decide what they do.'
    }, {
      at: C.Object,
      text: 'One person can stop something. A group is needed to allow it.'
    }, {
      at: C.TakeBack,
      text: 'Anyone a thing is about can take it back.'
    }, {
      at: C.PhotoGone,
      text: 'Good neighbours honour the receipt. Nothing stops a screenshot.'
    }, {
      at: C.Close,
      until: 9999,
      text: ''
    }]
  }));
}
function CirclesAnimation() {
  const hostRef = React.useRef(null);
  React.useEffect(() => {
    const fire = () => window.dispatchEvent(new Event('resize'));
    const el = hostRef.current;
    let ro;
    if (el && typeof ResizeObserver !== 'undefined') {
      let lastW = 0,
        lastH = 0;
      ro = new ResizeObserver(entries => {
        for (const e of entries) {
          const {
            width,
            height
          } = e.contentRect;
          if (Math.abs(width - lastW) > 1 || Math.abs(height - lastH) > 1) {
            lastW = width;
            lastH = height;
            fire();
          }
        }
      });
      ro.observe(el);
      if (el.parentElement) ro.observe(el.parentElement);
    }
    const timers = [0, 60, 200, 600, 1500].map(ms => setTimeout(fire, ms));
    return () => {
      if (ro) ro.disconnect();
      timers.forEach(clearTimeout);
    };
  }, []);
  return /*#__PURE__*/React.createElement("div", {
    ref: hostRef,
    style: {
      width: '100%',
      height: '100%'
    }
  }, /*#__PURE__*/React.createElement(CompositionStage, {
    width: 1920,
    height: 1080,
    scenes: window.OM_SCENES,
    playback: window.OM_PLAYBACK,
    bg: "#0D1117"
  }, /*#__PURE__*/React.createElement(Piece, null)));
}
window.CirclesAnimation = CirclesAnimation;
})(window.React, {exports:{}}, {}, function(){ return {}; });
