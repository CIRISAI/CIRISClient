"""The nav's SHAPE and the reasons for it, emitted beside the screenshots.

WHY THIS SITS NEXT TO THE PICTURES. A designer looking at 53 screenshots can
see what the client renders and not one thing about why it is arranged that
way — and the arrangement is not arbitrary. It is the visible end of a
specification chain that starts in the CIRIS Constitution and passes through
two FSDs and a surface standard before it reaches a sidebar. Someone asked to
"re-envision the UX" without that chain will move surfaces that are where they
are on purpose, and leave alone the ones that genuinely drifted.

Every number here is READ, not restated. The namespace registry says so in as
many words — "read the count there, never from a number restated in prose" —
so the family count comes out of `namespace_registry.json` at build time and
carries the hash of the Part III text it was generated from.
"""

from __future__ import annotations

import json
from pathlib import Path

#: The Constitution's generated namespace registry. Sibling checkout.
REGISTRY = Path.home() / "CIRISConstitution" / "manifests" / "namespace_registry.json"


def namespaces() -> dict:
    """The constitutional family count, from the file that generates it."""
    if not REGISTRY.exists():
        return {}
    meta = json.loads(REGISTRY.read_text()).get("_meta", {})
    return {
        "cc_version": meta.get("cc_version"),
        "families": meta.get("n_families"),
        "components": meta.get("n_components"),
        "components_normative": meta.get("n_components_normative"),
        "per_component": meta.get("per_component", {}),
        "source": meta.get("source"),
        "source_sha256": meta.get("source_sha256"),
    }


#: THE REASON, in the terms it was actually decided in. Not the spec chain —
#: that is where it is written down, which is a different question from why.
PRINCIPLES = [
    {
        "id": "equality",
        "title": "Everyone is equal — so there is no admin",
        "source": "ciris.ai/constitutional-mesh",
        "url": "https://ciris.ai/constitutional-mesh",
        "quote": (
            "Any operator can create an equally valid root, and every client "
            "chooses which roots to trust. The shipped root is a default, not "
            "the root."
        ),
        "gist": (
            "The mesh has no hierarchy. Peers find each other through signed "
            "records rather than app stores, registries or domain names, and "
            "standing is earned rather than bought — non-transferable credits "
            "for contributions that are costly to fake."
        ),
        "ui": (
            "There is no admin tier to design for, and no user tier either. The "
            "same surfaces exist for everyone; what differs is what a person can "
            "attest to. So the familiar idiom — a greyed control with "
            "\u201cyou don't have permission\u201d — is the wrong one here. Nobody is "
            "above anybody. The honest message is about the flow, not the rank."
        ),
    },
    {
        "id": "contextual-integrity",
        "title": "Privacy is appropriate flow, not secrecy",
        "source": "ciris.ai/contextual-integrity",
        "url": "https://ciris.ai/contextual-integrity",
        "quote": (
            "Privacy is the appropriate flow of information. Every part of life "
            "has its own rules for how information should move."
        ),
        "gist": (
            "Five signed parameters decide whether a flow is appropriate: the "
            "data subject, the sender, the recipient, the information type, and "
            "the transmission principle — the rule the flow must follow. A "
            "doctor knowing your diagnosis fits the rules of that context; the "
            "same doctor selling it does not, \u201ceven if a form said they could\u201d."
        ),
        "ui": (
            "Every surface that shows data owes an answer to \u201cunder which rule am "
            "I seeing this?\u201d That is why a field is named by its constitutional "
            "family: the family IS the information type, one of the five "
            "parameters. Show a value without its scope and the screen has "
            "dropped a parameter the protocol enforces."
        ),
    },
    {
        "id": "attestation-set",
        "title": "What you can see is relative to the attestation set",
        "source": "ciris.ai/contextual-integrity · ciris.ai/constitutional-mesh",
        "url": "https://ciris.ai/contextual-integrity",
        "quote": (
            "Family-scoped data sends no directory advertisement. Outsiders "
            "cannot route to it, read it, or even learn that it exists."
        ),
        "gist": (
            "Access is not a permission bit on an account. It is a function of "
            "which CEG attestations are in play — signed claims a peer can "
            "verify — so two people on the same screen can correctly see "
            "different things, and neither is being restricted."
        ),
        "ui": (
            "EMPTY AND INVISIBLE ARE DIFFERENT STATES, and this is the one a "
            "redesign is most likely to get wrong. \u201cNothing here yet\u201d invites "
            "the person to add something. \u201cNot yours to know exists\u201d must not "
            "even hint that there is something to be let into — the protocol "
            "goes to the trouble of not advertising it, and the UI can give that "
            "away in a way the wire never would."
        ),
    },
    {
        "id": "six-principles",
        "title": "Six principles, none of which may override another",
        "source": "ciris.ai/values",
        "url": "https://ciris.ai/values",
        "quote": (
            "Promote sustainable adaptive coherence: the living conditions under "
            "which diverse sentient beings may pursue their own flourishing in "
            "justice and wonder."
        ),
        "gist": (
            "Beneficence, Non-maleficence, Integrity, Fidelity & Transparency, "
            "Respect for Autonomy, Justice. \u201cNo principle grants license to "
            "violate another\u201d — unresolved conflicts escalate to trusted humans "
            "rather than being resolved algorithmically."
        ),
        "ui": (
            "Several surfaces that look like developer tools are principles made "
            "visible, and cannot be tidied away: Audit, Logs and Telemetry are "
            "Integrity's \u201ctransparent, auditable reasoning\u201d; Manage Consent is "
            "Autonomy's revocability, which is why it is a first-class surface "
            "and not a settings row; Safety sits high in the rail because "
            "non-maleficence is foundational rather than a bolt-on. An error or "
            "loading state is Fidelity: communicating uncertainty truthfully is "
            "a principle, which is why every surface owes four states."
        ),
    },
]


#: What a redesign must not break, stated as rules rather than as background.
DESIGN_RULES = [
    (
        "Do not add a role hierarchy",
        "No admin view, no permission tiers, no \u201cupgrade to see this\u201d. Equality "
        "is structural, not a setting."
    ),
    (
        "Never render a value without its scope",
        "The constitutional family is the information type — one of the five "
        "parameters that make a flow appropriate. A number with no family is an "
        "unlabelled flow."
    ),
    (
        "Distinguish empty from invisible",
        "Empty invites action. Invisible must not advertise that anything exists. "
        "Same blank area on screen, opposite obligations."
    ),
    (
        "Four states per surface, always",
        "Populated, empty, loading, error. Error must be visually distinct from "
        "empty — CSD/3 requires it because communicating uncertainty is a "
        "principle, not a nicety."
    ),
    (
        "Group by scope, not by feature",
        "The five Commons layers — Agent, Family, Local Community, Global "
        "Communities, Global Commons — are the CONTEXTS whose norms decide "
        "appropriate flow. They are not a feature menu, and collapsing them into "
        "one would erase the distinction the whole model rests on."
    ),
    (
        "Consent is revocable, so show the way out",
        "Every surface that collects or shares owes a visible path to withdraw. "
        "Machine-readable and revocable is the standard the protocol holds."
    ),
]


#: Where it is written down, once the reason is understood.
LINEAGE = [
    {
        "id": "constitution",
        "title": "CIRIS Constitution — Part III, The Namespace",
        "where": "CIRISConstitution/constitution/part_3_the_namespace.md",
        "says": (
            "A federation needs a shared vocabulary before it can have a shared "
            "judgment. Part III is that vocabulary: every component's namespace "
            "families, generated into a registry rather than asserted in prose."
        ),
        "means_for_ui": (
            "The words on screen are not UI copy. A field belongs to a "
            "constitutional family, and the family is what makes it checkable."
        ),
    },
    {
        "id": "csd",
        "title": "CSD/3 — the lifecycle form",
        "where": "CIRISClient/CSD.md",
        "says": (
            "A surface is specified BEFORE it is built, and the specification is "
            "machine-checked. v3's central move: the CEG prefix IS the field "
            "identifier, so one pass validates both the vocabulary and the UI "
            "contract. “A specification whose claims cannot be checked is a "
            "document, not a contract.”"
        ),
        "means_for_ui": (
            "Every surface owes four states — populated, empty, loading, error — "
            "and a redesign that shows only the populated one is half a design."
        ),
    },
    {
        "id": "ui-language",
        "title": "FSD — The CIRIS UI language",
        "where": "CIRISClient/docs/FSD-ui-language.md",
        "says": (
            "One language, two halves. RENDERING (`interactive_config`, owned by "
            "the agent) declares what the UI shows; EXPOSURE (test tags, owned by "
            "the client) declares what it exposes. The seam is a naming rule: the "
            "outputs vocabulary of the first is the addressing vocabulary of the "
            "second."
        ),
        "means_for_ui": (
            "The intended model is HyperCard: a CARD has title, contents, sources, "
            "links and outputs; a STACK is an ordered list of cards; a FLOW is a "
            "stack across screens. Screens are the accident; cards are the unit."
        ),
    },
    {
        "id": "epistemic-nav",
        "title": "Epistemic Commons Framework navigation",
        "where": "client/shared/…/ui/nav/EpistemicNav.kt",
        "says": (
            "Groups are epistemic scopes, not feature folders. Safety sits high "
            "deliberately — “foundational, not a bolt-on”. The Commons group "
            "is the five CEG 0.6 cohort scopes, folded from seven."
        ),
        "means_for_ui": (
            "Which groups exist is a function of the PROBED node, not of the "
            "build: a node that gains a brain reveals the agent surfaces on the "
            "next probe rather than the next reinstall."
        ),
    },
    {
        "id": "first-run",
        "title": "FSD — Remote first-run claim",
        "where": "CIRISClient/docs/FSD-remote-first-run-claim.md",
        "says": (
            "Only ONE setup route is reachable off-host, and the consequences of "
            "that are stated plainly rather than designed around."
        ),
        "means_for_ui": (
            "First-run is a constrained flow, not a screen that can be "
            "rearranged freely."
        ),
    },
]

#: What the atlas itself showed about the shape — findings, not doctrine.
OBSERVATIONS = [
    (
        "Two axes, not one",
        "A surface has a GROUP (the rail section it is filed under) and a PARENT "
        "(the chevron that reveals it). They are independent. Config, Runtime and "
        "System are filed under <b>node</b> while hanging off AgentSettings, which "
        "is filed under <b>agent</b>; GraphMemory is filed under node beneath "
        "Memory. The nav calls this deliberate — the agent framing alongside the "
        "node-infra framing — but a reader expecting one tree mis-reads all four."
    ),
    (
        "Thirteen surfaces have no group",
        "They reach the rail only through a parent's chevron: Sessions under "
        "Interact, Scheduler under Tickets, LLM Settings and Skills under Agent "
        "Settings, Audit and Consent under Data, Wallet under Billing, Logs under "
        "Telemetry, and the four Layer children. Collapse the parent and they are "
        "gone — which is why they were the last screens the atlas could reach."
    ),
    (
        "One surface has no route at all",
        "AccordCeremony is in no group and under no parent. Its own declaration "
        "says it opens from the Accord screen only while no accord family exists "
        "yet, so it is a flow, not a destination."
    ),
    (
        "The row and the chevron are different controls",
        "Clicking a parent row NAVIGATES to it; opening its children needs the "
        "chevron beside it. A person who clicks the word expecting the subtree "
        "gets the parent screen instead — the same confusion that cost the atlas "
        "a third of its coverage."
    ),
]
