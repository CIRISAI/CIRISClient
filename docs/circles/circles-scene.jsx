// Circles explainer — one continuous composition.
// Everything renders all the time from T; nothing mounts per section.

const { useComposition, Shot, Captions, animate, interpolate, Easing, clamp, CompositionStage } = window;

const CX = 660, CY = 540;
const RINGS = [
  { name: 'Just me',    r: 96,  color: '#22C0E8' },
  { name: 'Family',     r: 186, color: '#419CA0' },
  { name: 'Neighbours', r: 282, color: '#7A6FD6' },
  { name: 'Work',       r: 384, color: '#C96A38' },
  { name: 'Everyone',   r: 492, color: '#B08A3E' }
];

const MOTION = {
  enter: (start, end) => animate({ from: 0, to: 1, start, end, ease: Easing.easeOutCubic }),
  draw:  (start, end) => animate({ from: 0, to: 1, start, end, ease: Easing.easeInOutCubic }),
  pop:   (start, end) => animate({ from: 0, to: 1, start, end, ease: Easing.easeOutBack })
};

function track(T, points, ease) {
  const e = ease || Easing.easeInOutCubic;
  if (T <= points[0][0]) return points[0][1];
  for (let i = 0; i < points.length - 1; i++) {
    const [t0, v0] = points[i], [t1, v1] = points[i + 1];
    if (T <= t1) {
      if (t1 <= t0) return v1;
      return animate({ from: v0, to: v1, start: t0, end: t1, ease: e })(T);
    }
  }
  return points[points.length - 1][1];
}

const polar = (r, deg) => [CX + r * Math.cos(deg * Math.PI / 180), CY + r * Math.sin(deg * Math.PI / 180)];

const CONTACTS = [
  { ring: 1, a: 100,  label: 'Dad' },
  { ring: 1, a: 160,  label: 'Mira' },
  { ring: 1, a: -150, label: 'Sam' },
  { ring: 2, a: 50,   label: 'Clinic' },
  { ring: 2, a: 96,   label: 'Class 4B' },
  { ring: 2, a: 142,  label: 'A. Kaur' },
  { ring: 2, a: -170, label: 'Watch' },
  { ring: 2, a: -122, label: 'J. Adeyemi' },
  { ring: 3, a: -128, label: 'Datum Labs' },
  { ring: 3, a: 74,   label: 'Guild' },
  { ring: 4, a: -95,  label: 'ciris-root' },
  { ring: 4, a: 35,   label: 'commons-safety' },
  { ring: 4, a: 160,  label: 'peers' }
];

const MONO = "'Geist Mono', ui-monospace, monospace";
const SANS = "Geist, ui-sans-serif, system-ui, sans-serif";

function Panel(props) {
  return (
    <div style={{
      position: 'absolute', left: 1180, top: props.top, width: 660,
      background: '#151B24', border: '1px solid rgba(255,255,255,0.1)', borderRadius: 18,
      padding: '30px 34px', display: 'flex', flexDirection: 'column', gap: 18,
      opacity: props.o, transform: 'translateY(' + (1 - props.o) * 18 + 'px)'
    }}>{props.children}</div>
  );
}

function Eyebrow(props) {
  return <div style={{ font: '500 22px ' + MONO, letterSpacing: '0.12em', textTransform: 'uppercase', color: props.color }}>{props.children}</div>;
}
function Head(props) {
  return <div style={{ font: '600 34px ' + SANS, letterSpacing: '-0.015em', lineHeight: 1.2 }}>{props.children}</div>;
}
function Body(props) {
  return <div style={{ font: '400 23px ' + SANS, lineHeight: 1.5, color: '#9AA3AF' }}>{props.children}</div>;
}

// a small photo card, drawn in CSS only
function Photo(props) {
  return (
    <div style={{
      width: 132, height: 104, borderRadius: 12, overflow: 'hidden',
      background: '#2A3A44', border: '2px solid #F4F5F7',
      boxShadow: '0 12px 40px rgba(0,0,0,0.5)', position: 'relative',
      filter: props.blur ? 'blur(' + props.blur + 'px)' : 'none'
    }}>
      <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(180deg,#5B7F93 0%,#8AA9AE 58%,#C7B893 100%)' }} />
      <div style={{ position: 'absolute', left: 16, top: 14, width: 26, height: 26, borderRadius: '50%', background: '#F7E6B8' }} />
      <div style={{ position: 'absolute', left: -6, bottom: -18, width: 96, height: 62, borderRadius: '50%', background: '#4B6B52' }} />
      <div style={{ position: 'absolute', right: -14, bottom: -24, width: 104, height: 70, borderRadius: '50%', background: '#3E5C46' }} />
    </div>
  );
}

function Piece() {
  const { T, CUES, authoredTotal } = useComposition();

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
  const fin = (v, f) => (Number.isFinite(v) ? v : f);

  // ── the form's journey ──────────────────────────────────────────────
  const famPos = polar(RINGS[1].r, 20);
  const comPos = polar(RINGS[2].r, 12);

  const px = track(T, [
    [C.Born, CX], [C.Move + 3.2, CX], [C.Move + 6.0, famPos[0]],
    [C.Move + 9.2, famPos[0]], [C.Move + 12.4, comPos[0]],
    [C.TakeBack + 3.6, comPos[0]], [C.PhotoGone + 9.2, CX]
  ]);
  const py = track(T, [
    [C.Born, CY], [C.Move + 3.2, CY], [C.Move + 6.0, famPos[1]],
    [C.Move + 9.2, famPos[1]], [C.Move + 12.4, comPos[1]],
    [C.TakeBack + 3.6, comPos[1]], [C.PhotoGone + 9.2, CY]
  ]);
  const docIn = fin(MOTION.pop(C.Born + 0.4, C.Born + 2.4)(T), 0);
  const docOut = 1 - fin(MOTION.draw(C.Close + 0.2, C.Close + 2.2)(T), 0);
  // the form steps off while the photo's story runs (Photo → Move): the r=96 inner
  // circle cannot hold both cards, so only one is ever on stage at the centre.
  const docShow = clamp(
    1 - fin(MOTION.draw(C.Photo - 2.0, C.Photo - 0.4)(T), 0)
      + fin(MOTION.draw(C.Move - 2.0, C.Move - 0.4)(T), 0), 0, 1);
  const docScale = fin(track(T, [[C.Born, 1], [C.Move + 12.4, 1], [C.Object, 0.86]], Easing.easeOutCubic), 1);
  const docX = fin(px, CX), docY = fin(py, CY);

  // ── the photo's journey (its own story, told in the gaps) ───────────
  const pFam = polar(RINGS[1].r, -14);
  const pCom = polar(RINGS[2].r, -48);
  const phx = track(T, [
    [C.Photo, CX - 26], [C.Photo + 6, CX - 26],
    [C.PhotoOut + 3.0, pFam[0]], [C.PhotoOut + 7.0, pFam[0]],
    [C.PhotoOut + 10.5, pCom[0]], [C.PhotoGone + 4.0, pCom[0]],
    [C.PhotoGone + 7.5, CX - 26]
  ]);
  const phy = track(T, [
    [C.Photo, CY + 44], [C.Photo + 6, CY + 44],
    [C.PhotoOut + 3.0, pFam[1]], [C.PhotoOut + 7.0, pFam[1]],
    [C.PhotoOut + 10.5, pCom[1]], [C.PhotoGone + 4.0, pCom[1]],
    [C.PhotoGone + 7.5, CY + 44]
  ]);
  const photoIn = fin(MOTION.pop(C.Photo + 0.4, C.Photo + 2.4)(T), 0);
  const photoX = fin(phx, CX - 26), photoY = fin(phy, CY + 44);
  const photoBlur = 9 * MOTION.draw(C.PhotoLabel + 3.0, C.PhotoLabel + 5.5)(T)
                  * (1 - MOTION.draw(C.Object + 1.0, C.Object + 3.0)(T));
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

  return (
    <div style={{ position: 'absolute', inset: 0, fontFamily: SANS, color: '#F4F5F7', overflow: 'hidden' }}>

      {/* ── intro: the one big box everything goes into ───────────────── */}
      <div style={{
        position: 'absolute', left: CX - 250, top: CY - 170, width: 500, height: 340,
        borderRadius: 22, border: '2px solid #4A5260', background: '#161B22',
        opacity: boxIn * introOut, transform: 'scale(' + (0.94 + 0.06 * boxIn) + ')'
      }}>
        <div style={{ position: 'absolute', left: 0, right: 0, top: 26, textAlign: 'center', font: '600 30px ' + SANS, color: '#8A93A0' }}>
          big tech datacenter
        </div>
        <div style={{
          position: 'absolute', left: '50%', top: 150, transform: 'translate(-50%,-50%) rotate(-4deg) scale(' + (0.6 + 0.4 * upIn) + ')',
          opacity: upIn
        }}>
          <Photo />
          <div style={{
            position: 'absolute', left: '50%', top: 116, transform: 'translateX(-50%)',
            font: '500 22px ' + MONO, color: '#C6CCD4', whiteSpace: 'nowrap'
          }}>your photo</div>
        </div>
        <div style={{
          position: 'absolute', left: 40, right: 40, bottom: 34, textAlign: 'center',
          font: '400 23px ' + SANS, color: '#6B7280', opacity: MOTION.enter(C.Today + 6.5, C.Today + 9.0)(T)
        }}>
          you keep the memory. they keep the copy.
        </div>
        <div style={{
          position: 'absolute', left: '50%', top: '50%', width: 420, height: 6,
          transform: 'translate(-50%,-50%) rotate(-10deg) scaleX(' + strikeIn + ')',
          background: '#F87171', borderRadius: 999, opacity: strikeIn * introOut
        }} />
      </div>

      {[['Ads', -190], ['AI training', 0], ['Partners', 190]].map((c, i) => {
        const o = copyOut(i) * introOut;
        return (
          <div key={c[0]} style={{
            position: 'absolute', left: CX + c[1], top: CY + 230 + (1 - o) * -60,
            transform: 'translate(-50%,-50%)', opacity: o,
            display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10
          }}>
            <div style={{ width: 66, height: 52, borderRadius: 8, background: '#2A3A44', border: '2px solid #6B7280', opacity: 0.9 }} />
            <span style={{ font: '500 24px ' + MONO, color: '#9AA3AF', whiteSpace: 'nowrap' }}>{c[0]}</span>
          </div>
        );
      })}

      {/* ── transition: CIRIS is built open, free, and different ─────── */}
      <div style={{
        position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column',
        alignItems: 'center', justifyContent: 'center', gap: 34,
        opacity: signetIn * signetOut, pointerEvents: 'none'
      }}>
        <img src="ciris-signet.svg" alt="" style={{
          width: 240, height: 'auto',
          transform: 'scale(' + (0.86 + 0.14 * signetIn) + ') rotate(' + ((1 - signetIn) * -14) + 'deg)',
          filter: 'brightness(0) invert(1)'
        }} />
        <div style={{ font: '600 66px ' + SANS, letterSpacing: '-0.03em', textAlign: 'center', lineHeight: 1.12 }}>
          CIRIS is built open,<br />free, and different.
        </div>
        <div style={{
          display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6,
          background: '#151B24', border: '1px solid rgba(34,192,232,0.3)', borderRadius: 12,
          padding: '16px 26px',
          opacity: MOTION.enter(C.Different + 4.5, C.Different + 6.5)(T)
        }}>
          <span style={{ font: '500 28px ' + MONO, color: '#22C0E8' }}>ciris.ai</span>
          <span style={{ font: '400 21px ' + SANS, color: '#9AA3AF' }}>the whole thing, in the open</span>
        </div>
      </div>

      {RINGS.map((ring, i) => {
        const o = ringsIn(i);
        return (
          <div key={ring.name}>
            <div style={{
              position: 'absolute', left: CX - ring.r, top: CY - ring.r,
              width: ring.r * 2, height: ring.r * 2, borderRadius: '50%',
              border: '1.5px solid ' + ring.color, opacity: o * 0.5 * dim,
              transform: 'scale(' + (0.88 + 0.12 * o) + ')',
              background: i === 0 ? ring.color + '14' : 'transparent'
            }} />
            <div style={{
              position: 'absolute', left: CX - 4, top: CY - ring.r - 42,
              transform: 'translateX(-50%)', opacity: o * dim,
              font: '500 27px ' + MONO, letterSpacing: '0.04em', color: ring.color, whiteSpace: 'nowrap'
            }}>{ring.name}</div>
          </div>
        );
      })}

      {CONTACTS.map((c, i) => {
        const o = contactsIn(i);
        const [x, y] = polar(RINGS[c.ring].r, c.a);
        const color = RINGS[c.ring].color;
        const isObjector = c.label === 'A. Kaur';
        const objecting = isObjector && T > C.Object + 1.6 && T < C.TakeBack
          ? 0.5 + 0.5 * Math.sin((T - C.Object) * 3.2) : 0;
        return (
          <div key={c.label} style={{ position: 'absolute', left: x, top: y, transform: 'translate(-50%,-50%)', opacity: o * dim }}>
            <div style={{
              width: 30, height: 30, borderRadius: 10, background: color + '33',
              border: '1.5px solid ' + (objecting ? '#C96A38' : color),
              boxShadow: objecting ? '0 0 0 ' + (4 + 8 * objecting) + 'px rgba(201,106,56,' + (0.22 * objecting) + ')' : 'none',
              transform: 'scale(' + (0.7 + 0.3 * o) + ')'
            }} />
            <div style={{
              position: 'absolute', left: '50%', top: 38, transform: 'translateX(-50%)',
              font: '500 25px ' + MONO, color: '#C6CCD4', whiteSpace: 'nowrap',
              textShadow: '0 2px 12px #0D1117, 0 0 8px #0D1117'
            }}>{c.label}</div>
          </div>
        );
      })}

      {/* the photo */}
      <div style={{
        position: 'absolute', left: photoX, top: photoY,
        transform: 'translate(-50%,-50%) scale(' + (0.6 + 0.4 * photoIn) * (0.85 + 0.15 * photoOut) + ') rotate(-4deg)',
        opacity: photoIn * photoOut
      }}>
        <Photo blur={photoBlur} />
        <div style={{
          position: 'absolute', left: '50%', top: 116, transform: 'translateX(-50%)',
          font: '500 22px ' + MONO, color: '#C6CCD4', whiteSpace: 'nowrap',
          textShadow: '0 2px 12px #0D1117, 0 0 8px #0D1117'
        }}>beach.jpg</div>
      </div>

      {/* the form */}
      <div style={{
        position: 'absolute', left: docX, top: docY,
        transform: 'translate(-50%,-50%) scale(' + (docScale * (0.6 + 0.4 * docIn) * (0.82 + 0.18 * docOut) * (0.9 + 0.1 * docShow)) + ')',
        opacity: docIn * docOut * docShow, width: 124, height: 152, borderRadius: 13,
        background: '#F4F5F7', border: '1px solid rgba(255,255,255,0.4)',
        boxShadow: '0 12px 40px rgba(0,0,0,0.55)', padding: '14px 12px',
        display: 'flex', flexDirection: 'column', gap: 7
      }}>
        <div style={{ height: 7, width: '74%', background: '#0D1117', opacity: 0.8, borderRadius: 2 }} />
        <div style={{ height: 5, width: '100%', background: '#0D1117', opacity: 0.22, borderRadius: 2 }} />
        <div style={{ height: 5, width: '92%', background: '#0D1117', opacity: 0.22, borderRadius: 2 }} />
        <div style={{ height: 5, width: '96%', background: '#0D1117', opacity: 0.22, borderRadius: 2 }} />
        <div style={{ height: 5, width: '60%', background: '#0D1117', opacity: 0.22, borderRadius: 2 }} />
        <div style={{ marginTop: 'auto', font: '500 14px ' + MONO, color: '#4A5260' }}>form.pdf</div>
      </div>

      {/* ── panels ─────────────────────────────────────────────────────── */}

      <Shot from={0} to={C.Copies}>
        <Panel top={360} o={boxIn}>
          <Eyebrow color="#8A93A0">how it works today</Eyebrow>
          <Head>You post a photo. It goes to a big tech datacenter.</Head>
          <Body>Not yours. Theirs.</Body>
        </Panel>
      </Shot>

      <Shot from={C.Copies} to={C.NoTakeBack}>
        <Panel top={340} o={MOTION.enter(C.Copies + 0.3, C.Copies + 2.0)(T)}>
          <Eyebrow color="#8A93A0">then it spreads</Eyebrow>
          <Head>Copies go out. Nobody asks you first.</Head>
          <Body>A form said yes once, years ago.</Body>
        </Panel>
      </Shot>

      <Shot from={C.NoTakeBack} to={C.Different}>
        <Panel top={340} o={MOTION.enter(C.NoTakeBack + 0.3, C.NoTakeBack + 2.0)(T)}>
          <Eyebrow color="#F87171">and you cannot undo it</Eyebrow>
          <Head>Delete removes it from your screen.</Head>
          <Body>The copies stay. You cannot see them, ask about them, or call them back.</Body>
          <Body>CIRIS starts somewhere else.</Body>
        </Panel>
      </Shot>

      <Shot from={C.Different - 0.3} to={C.Open}>
        <Panel top={760} o={MOTION.enter(C.Different + 6.0, C.Different + 8.0)(T)}>
          <Body>No owner. No ads. Nothing to sell, because there is nothing to collect.</Body>
        </Panel>
      </Shot>

      <Shot from={C.Envelope - 0.3} to={C.Photo}>
        <Panel top={260} o={MOTION.enter(C.Envelope, C.Envelope + 1.6)(T)}>
          <Eyebrow color="#22C0E8">what travels with it</Eyebrow>
          <Body>Five facts go with it, every time.</Body>
          {[
            ['Who it is about', 'you and Mira'],
            ['Who sent it', 'your key'],
            ['Who can see it', 'just me'],
            ['What it is', 'a document'],
            ['The rule it follows', 'family only']
          ].map((row, i) => {
            const o = MOTION.enter(C.Envelope + 2.4 + i * 2.6, C.Envelope + 4.4 + i * 2.6)(T);
            const last = i === 4;
            return (
              <div key={row[0]} style={{ opacity: o, transform: 'translateX(' + (1 - o) * 14 + 'px)', display: 'flex', flexDirection: 'column', gap: 4, borderBottom: '1px solid rgba(255,255,255,0.07)', paddingBottom: 12 }}>
                <div style={{ display: 'flex', gap: 14, alignItems: 'baseline', flexWrap: 'wrap' }}>
                  <span style={{ font: '400 24px ' + SANS, color: last ? '#22C0E8' : '#8A93A0' }}>{row[0]}</span>
                  <code style={{ font: '500 24px ' + MONO, color: '#F4F5F7' }}>{row[1]}</code>
                </div>
              </div>
            );
          })}
        </Panel>
      </Shot>

      <Shot from={C.Photo} to={C.PhotoOut}>
        <Panel top={340} o={MOTION.enter(C.Photo + 0.3, C.Photo + 1.8)(T)}>
          <Eyebrow color="#22C0E8">a picture, from the start</Eyebrow>
          <Head>You take a photo.</Head>
          <Body>It starts with you. Nobody else has it.</Body>
        </Panel>
      </Shot>

      <Shot from={C.PhotoOut} to={C.Move}>
        <Panel top={330} o={MOTION.enter(C.PhotoOut + 0.3, C.PhotoOut + 1.8)(T)}>
          <Eyebrow color="#419CA0">you decide to share it</Eyebrow>
          <Head>{T < C.PhotoOut + 7.0 ? 'To your family' : 'Then to the neighbours'}</Head>
          {(T < C.PhotoOut + 7.0
            ? ['Three people can see it.', 'Nobody outside can even tell it exists.']
            : ['Thirty-one people can see it.', 'Some of them you never picked yourself.']
          ).map((f, i) => {
            const base = T < C.PhotoOut + 7.0 ? C.PhotoOut + 2.0 : C.PhotoOut + 8.4;
            const o = MOTION.enter(base + i * 1.4, base + 3.0 + i * 1.4)(T);
            return (
              <div key={i} style={{ opacity: o, display: 'flex', gap: 14, alignItems: 'flex-start', font: '400 25px ' + SANS, lineHeight: 1.45, color: '#9AA3AF' }}>
                <span style={{ color: '#419CA0' }}>→</span><span>{f}</span>
              </div>
            );
          })}
          <Body>One tap. One clear question.</Body>
        </Panel>
      </Shot>

      <Shot from={C.Move} to={C.Content}>
        <Panel top={350} o={MOTION.enter(C.Move + 0.3, C.Move + 1.8)(T)}>
          <Eyebrow color="#FBBF24">the form goes out too</Eyebrow>
          <Head>{T < C.Move + 9.2 ? 'Out to the family' : 'Out to the neighbours'}</Head>
          <Body>Going out asks. Coming back never does.</Body>
          <div style={{ display: 'flex', gap: 12, marginTop: 6 }}>
            <div style={{ background: '#419CA0', color: '#08131A', font: '600 23px ' + SANS, borderRadius: 10, padding: '14px 24px' }}>Move it</div>
            <div style={{ border: '1px solid rgba(255,255,255,0.16)', color: '#C6CCD4', font: '400 23px ' + SANS, borderRadius: 10, padding: '14px 24px' }}>Leave it</div>
          </div>
        </Panel>
      </Shot>

      <Shot from={C.Content} to={C.PhotoLabel}>
        <Panel top={280} o={MOTION.enter(C.Content + 0.3, C.Content + 1.8)(T)}>
          <Eyebrow color="#7A6FD6">files come first</Eyebrow>
          <Body>Every circle has a Files tab.</Body>
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center' }}>
            {['my rules', 'two helpers', 'newest first'].map((f, i) => (
              <span key={f} style={{
                font: '500 21px ' + MONO, borderRadius: 999, padding: '9px 18px', whiteSpace: 'nowrap',
                opacity: MOTION.enter(C.Content + 2.0 + i * 1.1, C.Content + 3.6 + i * 1.1)(T),
                background: i < 2 ? '#7A6FD6' : 'transparent', color: i < 2 ? '#0D1117' : '#9AA3AF',
                border: '1px solid ' + (i < 2 ? '#7A6FD6' : 'rgba(255,255,255,0.16)')
              }}>{f}</span>
            ))}
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 12 }}>
            {['beach.jpg', 'form.pdf', 'walk.mp4', 'notes.docx', 'budget.pdf', 'plan.glb'].map((n, i) => {
              const o = MOTION.pop(C.Content + 5.4 + i * 0.8, C.Content + 7.2 + i * 0.8)(T);
              return (
                <div key={n} style={{ opacity: o, transform: 'scale(' + (0.9 + 0.1 * o) + ')', background: '#0F141B', border: '1px solid rgba(255,255,255,0.08)', borderRadius: 10, overflow: 'hidden' }}>
                  <div style={{ height: 72, background: '#7A6FD629' }} />
                  <div style={{ padding: '8px 10px', display: 'flex', flexDirection: 'column', gap: 3 }}>
                    <span style={{ font: '500 21px ' + SANS }}>{n}</span>
                    <span style={{ font: '400 18px ' + MONO, color: '#8A93A0' }}>on 3 devices</span>
                  </div>
                </div>
              );
            })}
          </div>
          <Body>You choose the order, and whose opinions count.</Body>
        </Panel>
      </Shot>

      <Shot from={C.PhotoLabel} to={C.Object}>
        <Panel top={340} o={MOTION.enter(C.PhotoLabel + 0.3, C.PhotoLabel + 1.8)(T)}>
          <Eyebrow color="#B08A3E">someone tags the photo</Eyebrow>
          <Head>A neighbour adds a note.</Head>
          <Body>They cannot delete it. A note is an opinion, not an order.</Body>
          <Body>Your rules say blur it. So it blurs.</Body>
        </Panel>
      </Shot>

      <Shot from={C.Object} to={C.TakeBack}>
        <Panel top={300} o={MOTION.enter(C.Object + 0.3, C.Object + 1.8)(T)}>
          <Eyebrow color="#C96A38">how the group steps in</Eyebrow>
          <Head>One person can pull the brake. It takes a group to let go of it.</Head>
          {[
            ['Anyone speaks up', 'one person is enough — always, in every group', objFill, '#C96A38'],
            ['The named helpers answer', 'or they do not answer at all', stewFill, '#FBBF24'],
            ['If nobody answers', 'the group decides instead, and never fewer than three', escFill, '#4ADE80']
          ].map(row => (
            <div key={row[0]} style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <div style={{ display: 'flex', gap: 12, alignItems: 'baseline', flexWrap: 'wrap' }}>
                <b style={{ font: '600 25px ' + SANS, color: row[3] }}>{row[0]}</b>
                <span style={{ font: '400 22px ' + SANS, color: '#9AA3AF' }}>{row[1]}</span>
              </div>
              <div style={{ height: 12, borderRadius: 999, background: 'rgba(255,255,255,0.07)', overflow: 'hidden' }}>
                <div style={{ height: '100%', width: (row[2] * 100) + '%', background: row[3], borderRadius: 999 }} />
              </div>
            </div>
          ))}
          <Body>So it never waits for someone in charge.</Body>
        </Panel>
      </Shot>

      <Shot from={C.TakeBack} to={C.PhotoGone}>
        <Panel top={360} o={MOTION.enter(C.TakeBack + 0.3, C.TakeBack + 1.8)(T)}>
          <Eyebrow color="#4ADE80">taking it back</Eyebrow>
          <Head>Mira is in the photo.</Head>
          <Body>So Mira can take it back. Not just the person who shared it.</Body>
        </Panel>
      </Shot>

      <Shot from={C.PhotoGone} to={C.Close}>
        <Panel top={340} o={MOTION.enter(C.PhotoGone + 0.3, C.PhotoGone + 1.8)(T)}>
          <Eyebrow color="#22C0E8">the photo comes home</Eyebrow>
          <Head>Back to just me.</Head>
          <Body>The photo carries its receipt everywhere it goes. Apps that play fair read it, and honour it.</Body>
          <Body>What we cannot stop is a screenshot, or someone who breaks the rules on purpose.</Body>
          <Body>So this is not a lock. It is a clear, signed request that good neighbours keep.</Body>
        </Panel>
      </Shot>

      <Shot from={C.Close - 0.2} to={total}>
        <div style={{
          position: 'absolute', left: 0, right: 0, top: 340, display: 'flex', flexDirection: 'column',
          alignItems: 'center', gap: 24, opacity: closeIn
        }}>
          <div style={{ font: '600 64px ' + SANS, letterSpacing: '-0.03em', textAlign: 'center', lineHeight: 1.12 }}>
            Starts close to you.<br />Moves out only when you say so.<br />Comes back if anyone in it asks.
          </div>
          <div style={{ font: '400 26px ' + SANS, color: '#9AA3AF', textAlign: 'center', maxWidth: 940, lineHeight: 1.5 }}>
Privacy is not hiding things. It is things moving the way people expect.
          </div>
          <div style={{
            marginTop: 26, display: 'flex', gap: 14, flexWrap: 'wrap', justifyContent: 'center',
            opacity: MOTION.enter(C.Close + 2.4, C.Close + 4.4)(T)
          }}>
            {[
              ['ciris.ai/contextual-integrity', 'the five facts, in full'],
              ['ciris.ai/safety', 'the stop button nobody can talk past']
            ].map(l => (
              <div key={l[0]} style={{
                background: '#151B24', border: '1px solid rgba(34,192,232,0.3)', borderRadius: 12,
                padding: '16px 22px', display: 'flex', flexDirection: 'column', gap: 5, textAlign: 'left'
              }}>
                <span style={{ font: '500 26px ' + MONO, color: '#22C0E8' }}>{l[0]}</span>
                <span style={{ font: '400 21px ' + SANS, color: '#9AA3AF' }}>{l[1]}</span>
              </div>
            ))}
          </div>
        </div>
      </Shot>

      <Captions items={[
        { at: 0, text: 'Today, what you make goes to a big tech datacenter. Not yours.' },
        { at: C.Copies, text: 'Copies go out to people you never picked.' },
        { at: C.NoTakeBack, text: 'Delete hides it from you. The copies stay.' },
        { at: C.Different, text: 'CIRIS is built open, free, and different.' },
        { at: C.Open, text: 'Five circles. Closest to you first.' },
        { at: C.Contacts, text: 'A circle is just the people in it.' },
        { at: C.Born, text: 'New things start in the middle, with you.' },
        { at: C.Envelope, text: 'Five facts travel with everything.' },
        { at: C.Photo, text: 'You take a photo. It starts with you.' },
        { at: C.PhotoOut, text: 'Sharing is a step you choose, one circle at a time.' },
        { at: C.Move, text: 'Going out asks. Coming back never does.' },
        { at: C.Content, text: 'Files come first. Every circle has them.' },
        { at: C.PhotoLabel, text: 'Tags are opinions. Your rules decide what they do.' },
        { at: C.Object, text: 'One person can stop something. A group is needed to allow it.' },
        { at: C.TakeBack, text: 'Anyone a thing is about can take it back.' },
        { at: C.PhotoGone, text: 'Good neighbours honour the receipt. Nothing stops a screenshot.' },
        { at: C.Close, until: 9999, text: '' }
      ]} />
    </div>
  );
}

function CirclesAnimation() {
  const hostRef = React.useRef(null);
  React.useEffect(() => {
    const fire = () => window.dispatchEvent(new Event('resize'));
    const el = hostRef.current;
    let ro;
    if (el && typeof ResizeObserver !== 'undefined') {
      let lastW = 0, lastH = 0;
      ro = new ResizeObserver(entries => {
        for (const e of entries) {
          const { width, height } = e.contentRect;
          if (Math.abs(width - lastW) > 1 || Math.abs(height - lastH) > 1) {
            lastW = width; lastH = height;
            fire();
          }
        }
      });
      ro.observe(el);
      if (el.parentElement) ro.observe(el.parentElement);
    }
    const timers = [0, 60, 200, 600, 1500].map(ms => setTimeout(fire, ms));
    return () => { if (ro) ro.disconnect(); timers.forEach(clearTimeout); };
  }, []);
  return (
    <div ref={hostRef} style={{ width: '100%', height: '100%' }}>
      <CompositionStage width={1920} height={1080} scenes={window.OM_SCENES} playback={window.OM_PLAYBACK} bg="#0D1117">
        <Piece />
      </CompositionStage>
    </div>
  );
}

window.CirclesAnimation = CirclesAnimation;
