#!/usr/bin/env python3
"""Translate missing localization keys into all supported languages, in CI.

    python3 client/tools/localize.py --check              # list what is missing, exit 1 if anything
    python3 client/tools/localize.py --mode fast          # translate now (PR-sized deltas)
    python3 client/tools/localize.py --mode batch         # Batch API, 50% cheaper (backfills)
    python3 client/tools/localize.py --mode fast --dry-run  # plan + token estimate, no API call

This replaces the interactive Claude Code fan-outs (the "87-agent fan-out" of
CIRISServer 0.5.185) with a pipeline stage. Same translator family, ~100x
cheaper, and the output faces the same gate either way:
check_localization_sync.py --strict decides what merges, not this script.

Cost design, in order of what actually saves money:
  1. DIFF-DRIVEN — only keys missing (or empty) in a locale are sent. A no-op
     run costs zero API calls, which is also the workflow's loop guard.
  2. BULK LANE on a small model — short UI strings are the case small models
     are good at; the guard catches what they get wrong.
  3. SHARED CACHEABLE PREFIX — the system prompt and the English source block
     are byte-identical across all 28 language requests and marked
     cache_control, so requests 2..28 read the prefix from cache (~90% off
     that portion) in fast mode.
  4. BATCH MODE — the same requests via the Message Batches API at 50% off,
     for backfills where nobody is waiting.
  5. REPAIR LANE on a stronger model — only for keys the validator rejects
     (placeholder corruption, missing keys), which is normally a handful.

Terminology consistency: each request carries anchor pairs — existing
translations of keys that share a dot-prefix family with the missing keys,
plus a small core set — so "node" stays Knoten/nœud/узел/节点 per the shipped
corpus rather than whatever the model would pick fresh.

Providers: ANTHROPIC_API_KEY drives the Anthropic SDK path (true Batches API,
prompt caching); OPENROUTER_API_KEY drives the OpenRouter path (same Claude
models at the same prices, `:batch` slugs at 50% off; caching not counted on).
LOCALIZE_PROVIDER=anthropic|openrouter forces one; otherwise whichever key is
set wins, Anthropic first. LOCALIZE_BULK_MODEL / LOCALIZE_REPAIR_MODEL
override the lanes (use the provider's own model ids).
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_localization_sync as guard  # the same semantics the gate enforces

REPO_ROOT = guard.REPO_ROOT
CANONICAL = REPO_ROOT / guard.CANONICAL_BUNDLE

def _provider() -> str:
    forced = os.environ.get("LOCALIZE_PROVIDER")
    if forced:
        return forced
    if os.environ.get("ANTHROPIC_API_KEY"):
        return "anthropic"
    if os.environ.get("OPENROUTER_API_KEY"):
        return "openrouter"
    return "anthropic"  # fails loudly at call time with the SDK's own message


PROVIDER = _provider()
_DEFAULTS = {
    "anthropic": ("claude-haiku-4-5", "claude-sonnet-5"),
    "openrouter": ("anthropic/claude-haiku-4.5", "anthropic/claude-sonnet-5"),
}
BULK_MODEL = os.environ.get("LOCALIZE_BULK_MODEL", _DEFAULTS[PROVIDER][0])
REPAIR_MODEL = os.environ.get("LOCALIZE_REPAIR_MODEL", _DEFAULTS[PROVIDER][1])

# $/MTok (input, output) — cached table for the cost REPORT only; billing is
# whatever the API bills. Update when lane models change.
PRICE = {
    "claude-haiku-4-5": (1.00, 5.00),
    "claude-sonnet-5": (2.00, 10.00),  # intro pricing through 2026-08-31
    "anthropic/claude-haiku-4.5": (1.00, 5.00),
    "anthropic/claude-sonnet-5": (2.00, 10.00),
}

CORE_ANCHOR_KEYS = (
    "mobile.contacts_title",
    "mobile.manage_nodes_title",
    "mobile.contacts_empty_body",
    "mobile.contacts_chat_not_started",
)

SYSTEM = """You translate UI strings for CIRIS, a decentralized mesh client \
shipped in 29 languages. You will receive the English source strings and must \
return translations for one target language.

Rules, all of them hard:
- Return ONLY a JSON object mapping each requested key to its translation. No
  markdown fences, no commentary.
- Preserve every placeholder EXACTLY as it appears in the English source:
  {named}, ${expr}, %s, %1$s. Never translate, rename, drop, or add one.
- Product terms stay untranslated: CIRIS, ciris-server, key_id, USB, and
  version numbers like 0.5.185.
- Match the register and terminology of the ANCHOR translations provided —
  they are shipped strings from the same product in the target language. If
  the anchors say vous/Sie/formal, you say vous/Sie/formal.
- These are UI strings: concise, natural, no explanations. A button label
  stays a button label; a sentence keeps its em-dash structure if natural in
  the target language, or uses the target language's equivalent punctuation.
- If a string cannot be translated without more context, translate it as
  faithfully as possible anyway — a reviewer sees everything; an empty value
  ships nothing."""


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def missing_by_language() -> tuple[dict, dict[str, list[str]]]:
    """en flat map, and per-language keys missing or empty."""
    en = load(CANONICAL / "en.json")
    en_flat = guard.flat_values(en)
    langs = [l for l in guard.manifest_languages(CANONICAL) if l != "en"]
    out: dict[str, list[str]] = {}
    for lang in langs:
        lang_flat = guard.flat_values(load(CANONICAL / f"{lang}.json"))
        need = [k for k in en_flat
                if k not in lang_flat or not str(lang_flat[k]).strip()]
        if need:
            out[lang] = sorted(need)
    return en_flat, out


def anchors_for(lang: str, needed: list[str], en_flat: dict) -> list[tuple[str, str, str]]:
    """(key, english, existing translation) pairs anchoring terminology."""
    lang_flat = guard.flat_values(load(CANONICAL / f"{lang}.json"))
    families = {k.rsplit(".", 1)[0] for k in needed}
    picked: list[tuple[str, str, str]] = []
    seen: set[str] = set()

    def take(key: str) -> None:
        if key in seen or key in needed:
            return
        if key in lang_flat and key in en_flat and str(lang_flat[key]).strip():
            picked.append((key, str(en_flat[key]), str(lang_flat[key])))
            seen.add(key)

    for fam in sorted(families):
        count = 0
        for k in sorted(en_flat):
            if k.rsplit(".", 1)[0] == fam and count < 3:
                before = len(picked)
                take(k)
                count += len(picked) - before
    for k in CORE_ANCHOR_KEYS:
        take(k)
    return picked[:30]


def source_block(needed_union: list[str], en_flat: dict) -> str:
    """The English source payload — IDENTICAL across languages, so it sits in
    the shared cacheable prefix. Sorted for byte-stability."""
    return json.dumps({k: en_flat[k] for k in sorted(needed_union)},
                      indent=1, ensure_ascii=False, sort_keys=True)


def request_messages(lang: str, needed: list[str], anchors, src: str) -> list[dict]:
    meta = load(CANONICAL / f"{lang}.json").get("_meta", {})
    lang_name = meta.get("language_name", lang)
    anchor_txt = "\n".join(f'  {k}: "{e}" -> "{t}"' for k, e, t in anchors)
    return [
        {
            "role": "user",
            "content": [
                {
                    "type": "text",
                    "text": "ENGLISH SOURCE STRINGS (the union for this run; "
                            "translate only the keys listed for your language "
                            "below):\n" + src,
                    # Requests differ only AFTER this block: system + source
                    # cache across all languages in this run.
                    "cache_control": {"type": "ephemeral"},
                },
                {
                    "type": "text",
                    "text": f"TARGET LANGUAGE: {lang} ({lang_name})\n\n"
                            f"ANCHOR TRANSLATIONS (shipped strings — match "
                            f"their terminology and register):\n{anchor_txt}\n\n"
                            f"TRANSLATE THESE KEYS:\n"
                            + "\n".join(f"- {k}" for k in needed),
                },
            ],
        }
    ]


def validate(lang: str, needed: list[str], en_flat: dict, got: dict) -> list[str]:
    """Failure reasons; empty list = clean."""
    problems = []
    for k in needed:
        v = got.get(k)
        if not isinstance(v, str) or not v.strip():
            problems.append(f"{k}: missing or empty")
            continue
        want = Counter(guard._PLACEHOLDER.findall(str(en_flat[k])))
        have = Counter(guard._PLACEHOLDER.findall(v))
        if want != have:
            problems.append(f"{k}: placeholders {dict(have)} != source {dict(want)}")
    for k in got:
        if k not in needed:
            problems.append(f"{k}: not requested")
    return problems


def parse_json_reply(text: str) -> dict:
    text = text.strip()
    if text.startswith("```"):
        text = text.strip("`")
        text = text[text.index("{"):]
    start, end = text.index("{"), text.rindex("}") + 1
    return json.loads(text[start:end])


def insert_translations(lang: str, values: dict[str, str], en: dict) -> None:
    """Write into all four mirrors at en.json's key positions."""
    def position_map(obj: dict, prefix="") -> dict[str, str | None]:
        # key -> predecessor sibling key (None = first)
        out = {}
        prev = None
        for k, v in obj.items():
            kk = f"{prefix}.{k}" if prefix else k
            out[kk] = prev
            prev = kk
            if isinstance(v, dict):
                out.update(position_map(v, kk))
        return out

    pred = position_map(en)
    path = CANONICAL / f"{lang}.json"
    doc = load(path)
    for key in sorted(values, key=lambda k: list(guard.flat_values(en)).index(k)):
        parts = key.split(".")
        node = doc
        en_node = en
        for p in parts[:-1]:
            en_node = en_node[p]
            node = node.setdefault(p, {})
        leaf = parts[-1]
        if leaf in node and str(node[leaf]).strip():
            continue
        want_pred = pred.get(key)
        want_pred_leaf = want_pred.split(".")[-1] if want_pred and "." in want_pred else want_pred
        rebuilt, placed = {}, False
        if want_pred_leaf and want_pred_leaf in node:
            for k, v in node.items():
                rebuilt[k] = v
                if k == want_pred_leaf:
                    rebuilt[leaf] = values[key]
                    placed = True
        if not placed:
            for k, v in node.items():
                if not placed and k > leaf:
                    rebuilt[leaf] = values[key]
                    placed = True
                rebuilt[k] = v
            if not placed:
                rebuilt[leaf] = values[key]
        node.clear()
        node.update(rebuilt)

    out = json.dumps(doc, indent=2, ensure_ascii=False) + "\n"
    for bundle in guard.MIRROR_BUNDLES:
        (REPO_ROOT / bundle / f"{lang}.json").write_text(out, encoding="utf-8")


class Reply:
    """Provider-neutral result of one model call."""

    def __init__(self, text: str, input_tokens: int, output_tokens: int,
                 cache_read: int = 0) -> None:
        self.text = text
        self.input_tokens = input_tokens
        self.output_tokens = output_tokens
        self.cache_read = cache_read


def _anthropic_call(model: str, messages: list[dict]) -> Reply:
    import anthropic

    client = anthropic.Anthropic()
    msg = client.messages.create(
        model=model, max_tokens=16000, system=SYSTEM, messages=messages)
    return Reply(
        next(b.text for b in msg.content if b.type == "text"),
        msg.usage.input_tokens, msg.usage.output_tokens,
        msg.usage.cache_read_input_tokens or 0)


def _openrouter_call(model: str, messages: list[dict]) -> Reply:
    """Same request over OpenRouter's endpoint. Content blocks are flattened —
    the cache_control lever doesn't survive this path, which is priced in:
    it's the fallback lane, and the :batch slugs still halve it."""
    import urllib.request

    flat = "\n\n".join(
        part["text"] for m in messages for part in m["content"])
    body = json.dumps({
        "model": model,
        "max_tokens": 16000,
        "messages": [{"role": "system", "content": SYSTEM},
                     {"role": "user", "content": flat}],
    }).encode()
    req = urllib.request.Request(
        "https://openrouter.ai/api/v1/chat/completions", data=body,
        headers={"Authorization": f"Bearer {os.environ['OPENROUTER_API_KEY']}",
                 "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as resp:
        data = json.loads(resp.read())
    if "error" in data:
        raise RuntimeError(f"openrouter: {data['error']}")
    usage = data.get("usage", {})
    return Reply(
        data["choices"][0]["message"]["content"],
        usage.get("prompt_tokens", 0), usage.get("completion_tokens", 0))


def call_model(model: str, messages: list[dict], *, batch: bool = False) -> Reply:
    if PROVIDER == "openrouter":
        return _openrouter_call(model + (":batch" if batch else ""), messages)
    return _anthropic_call(model, messages)


class Spend:
    def __init__(self) -> None:
        self.tokens: dict[str, list[int]] = {}

    def add(self, model: str, reply: Reply) -> None:
        t = self.tokens.setdefault(model, [0, 0, 0])
        t[0] += reply.input_tokens
        t[1] += reply.output_tokens
        t[2] += reply.cache_read

    def report(self, batch: bool = False) -> str:
        lines, total = [], 0.0
        for model, (inp, out, cached) in self.tokens.items():
            i_rate, o_rate = PRICE.get(model, (0, 0))
            cost = (inp * i_rate + out * o_rate) / 1e6
            if batch:
                cost /= 2
            total += cost
            lines.append(f"  {model}: {inp:,} in ({cached:,} cache-read) / "
                         f"{out:,} out ≈ ${cost:.4f}" + (" (batch −50%)" if batch else ""))
        lines.append(f"  total ≈ ${total:.4f}")
        return "\n".join(lines)


def translate(mode: str, max_keys: int, dry_run: bool) -> int:
    en_flat, missing = missing_by_language()
    if not missing:
        print("nothing missing — all 29 languages at parity; no API call made")
        return 0

    total_keys = sum(len(v) for v in missing.values())
    print(f"missing: {total_keys} value(s) across {len(missing)} language(s)")
    if total_keys > max_keys:
        print(f"[refuse] {total_keys} > --max-keys {max_keys}. A run this large "
              f"should be deliberate: re-run with --max-keys raised (and "
              f"consider --mode batch, 50% cheaper).")
        return 2

    union = sorted({k for keys in missing.values() for k in keys})
    src = source_block(union, en_flat)
    en = load(CANONICAL / "en.json")

    plans = {
        lang: request_messages(lang, needed, anchors_for(lang, needed, en_flat), src)
        for lang, needed in sorted(missing.items())
    }

    if dry_run:
        approx = sum(len(json.dumps(m)) for m in plans.values()) // 4
        print(f"[dry-run] {len(plans)} request(s), ~{approx:,} input tokens "
              f"(prefix ~{len(src) // 4:,} of those cached after request 1); "
              f"bulk={BULK_MODEL}, repair={REPAIR_MODEL}, mode={mode}")
        return 0

    spend = Spend()
    results: dict[str, Reply] = {}

    if mode == "batch" and PROVIDER == "anthropic":
        import anthropic
        from anthropic.types.message_create_params import MessageCreateParamsNonStreaming
        from anthropic.types.messages.batch_create_params import Request

        client = anthropic.Anthropic()
        batch = client.messages.batches.create(requests=[
            Request(custom_id=lang, params=MessageCreateParamsNonStreaming(
                model=BULK_MODEL, max_tokens=16000, system=SYSTEM, messages=msgs))
            for lang, msgs in plans.items()
        ])
        print(f"[batch] {batch.id} — {len(plans)} request(s), polling")
        while True:
            b = client.messages.batches.retrieve(batch.id)
            if b.processing_status == "ended":
                break
            time.sleep(30)
        for entry in client.messages.batches.results(batch.id):
            if entry.result.type == "succeeded":
                msg = entry.result.message
                results[entry.custom_id] = Reply(
                    next(bl.text for bl in msg.content if bl.type == "text"),
                    msg.usage.input_tokens, msg.usage.output_tokens,
                    msg.usage.cache_read_input_tokens or 0)
                spend.add(BULK_MODEL, results[entry.custom_id])
            else:
                print(f"[batch] {entry.custom_id}: {entry.result.type}")
    else:
        want_batch = mode == "batch"  # openrouter: the :batch slug, same loop
        for lang, msgs in plans.items():
            reply = call_model(BULK_MODEL, msgs, batch=want_batch)
            spend.add(BULK_MODEL, reply)
            results[lang] = reply
            print(f"[bulk] {lang}: ok (cache_read={reply.cache_read})")

    still_broken: dict[str, list[str]] = {}
    for lang, needed in sorted(missing.items()):
        reply = results.get(lang)
        got: dict = {}
        if reply is not None:
            try:
                got = parse_json_reply(reply.text)
            except ValueError as e:
                print(f"[bulk] {lang}: unparseable reply ({e})")
        problems = validate(lang, needed, en_flat, got)
        bad = sorted({p.split(":")[0] for p in problems if not p.endswith("not requested")})
        clean = {k: v for k, v in got.items() if k in needed and k not in bad}

        if bad:
            # Repair lane: only the rejected keys, with the reasons, on the
            # stronger model. One attempt; the gate decides after that.
            repair_msgs = request_messages(
                lang, bad, anchors_for(lang, bad, en_flat), src)
            repair_msgs[0]["content"][-1]["text"] += (
                "\n\nA previous attempt was REJECTED by the validator:\n"
                + "\n".join(f"  {p}" for p in problems if not p.endswith("not requested"))
                + "\nReturn corrected translations for exactly these keys."
            )
            reply2 = call_model(REPAIR_MODEL, repair_msgs)
            spend.add(REPAIR_MODEL, reply2)
            try:
                got2 = parse_json_reply(reply2.text)
            except ValueError:
                got2 = {}
            fixed = {k: v for k, v in got2.items()
                     if k in bad and not validate(lang, [k], en_flat, {k: v})}
            clean.update(fixed)
            leftover = sorted(set(bad) - set(fixed))
            if leftover:
                still_broken[lang] = leftover
            print(f"[repair] {lang}: {len(fixed)}/{len(bad)} recovered")

        if clean:
            insert_translations(lang, clean, en)
            print(f"[write] {lang}: {len(clean)} value(s), 4 mirrors")

    print("\nspend (estimate — billing is what the API bills):")
    print(spend.report(batch=(mode == "batch")))

    if still_broken:
        print("\n[FAIL-CLOSED] still missing after repair (the strict gate will "
              "block; translate by hand or re-run):")
        for lang, keys in sorted(still_broken.items()):
            print(f"  {lang}: {', '.join(keys)}")
        return 1
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true",
                    help="report missing values and exit (1 if any)")
    ap.add_argument("--mode", choices=["fast", "batch"], default="fast")
    ap.add_argument("--max-keys", type=int, default=400,
                    help="refuse runs larger than this many missing values (default 400)")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    if args.check:
        _, missing = missing_by_language()
        if not missing:
            print("all languages at parity")
            return 0
        for lang, keys in sorted(missing.items()):
            print(f"{lang}: {len(keys)} missing — {', '.join(keys[:5])}"
                  + (" …" if len(keys) > 5 else ""))
        return 1

    return translate(args.mode, args.max_keys, args.dry_run)


if __name__ == "__main__":
    sys.exit(main())
