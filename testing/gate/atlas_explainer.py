"""Start here: the middle-school explainer.

WHY THIS EXISTS. The "why" on this site was, twice, written for the wrong
reader — first as a chain of specifications, then as four principles with
quotes. Both were accurate and neither was usable by a person holding a pen.
So the text was put through a deliberate test: a child's version was written
from it, and two readers who saw ONLY the child's version reconstructed the
adult meaning without ever seeing the original.

Almost everything came back. Root-of-trust lists, certificate authorities,
non-transferable credentials, the five contextual-integrity parameters, the
transmission principle, machine-readable revocation — all recovered from
sentences about stickers and toys. The ideas were never the hard part. The
prose was.

What did NOT come back is what this page is built around. The designer who
read only the simple version could not name the shared vocabulary behind "the
group a thing belongs to", so they invented placeholder categories — while a
registry of 116 real families sits in the Constitution. They could not name
the identity scheme behind "a note with your own name on it", so they could
not say what the add-a-contact field must accept. They could not tell whether
"no promise beats another" had any positive expression on screen. Each of
those gaps is answered below, by name.
"""

from __future__ import annotations

#: The spine. Five scopes, in the order a person actually grows outward.
CIRCLES = [
    ("Agent (Self)", "layer-agent",
     "You and the agent that works for you. Your own reasoning, your own notes."),
    ("Family", "layer-family",
     "The people you live your closest life with. The strictest circle. The "
     "mechanism has a name: family-scoped data <b>sends no directory "
     "advertisement</b> — nothing is announced on the discovery destination — "
     "so outsiders cannot route to it, read it, or learn it exists."),
    ("Local Community", "layer-local-community",
     "Where you actually are. Neighbours, a school, a clinic, a town."),
    ("Global Communities", "layer-global-communities",
     "The groups you choose to belong to, wherever they are. Affiliation "
     "rather than geography."),
    ("Global Commons", "layer-global-commons",
     "Everyone. What is genuinely shared, and the only circle where "
     "“public” means what people usually think it means."),
]

SECTIONS = [
    {
        "h": "The one idea, first",
        "body": [
            "Most software sorts information into <b>secret</b> and <b>public</b>. "
            "This one does not. Information belongs to a <b>circle</b>, and the "
            "harm is moving it out of the circle it belongs to.",
            "Your doctor knowing your symptom is fine — that is what the medical "
            "circle is for. The same doctor selling it is not fine. Nothing about "
            "the fact changed. What changed is which circle it moved into. That is "
            "the whole model, and everything below is machinery for enforcing it.",
            "So the first question any screen must be able to answer is not "
            "“is this allowed?” but <b>“which circle is this in, and which rule "
            "let it come here?”</b>",
        ],
    },
    {
        "h": "The circles, smallest first",
        "body": [
            "There are five, they are fixed, and they are the top level of the "
            "navigation for that reason. They are not a filter, a dropdown or a "
            "set of chips — they are the contexts whose rules decide whether a "
            "flow is appropriate. Collapse them into one and the model is gone.",
        ],
        "circles": True,
    },
    {
        "h": "Keep everything as low as it will go",
        "body": [
            "This is <b>subsidiarity</b>, and it comes from Catholic social "
            "teaching by way of <b>Magnifica Humanitas</b> — the Vatican's 2026 "
            "document, one of the four frameworks CIRIS maps itself to on "
            "<code>ciris.ai/compliance</code>. The old formulation: nothing should "
            "be done by a larger and higher body that can be done as well by a "
            "smaller and lower one.",
            "On the compliance page it appears as the dimension "
            "<code>locality:decision:{scale}</code> — “decision routing at lowest "
            "competent scale”. It is stated there about <b>decisions</b>. Applied "
            "to <b>data</b> it gives the same instruction, and contextual integrity "
            "says it in the other direction: family-scoped data does not leave the "
            "family, to the point of not announcing that it exists.",
            "<b>For the UI:</b> the default is always the smallest circle that "
            "works. A screen that quietly widens scope — a share control that "
            "defaults outward, a list that shows the global view first — is not a "
            "convenience. It is the one thing the whole design is against.",
        ],
    },
    {
        "h": "How the wire actually enforces it",
        "body": [
            "None of this is a promise in a policy document. It is in the bytes, "
            "and a designer can rely on it.",
            "<b>Every claim names two authorities, not one.</b> The envelope "
            "carries <code>attesting_key_id</code> — who said it — and "
            "<code>subject_key_ids</code> — who it is about. The Constitution is "
            "blunt about why: “Consent is incomplete if only the producer of data "
            "has authority over it… a person who appears in someone else's data "
            "can still pull it.”",
            "<b>Five things travel with every flow:</b> the subject, the sender, "
            "the recipient, the <b>information type</b>, and the <b>transmission "
            "principle</b> — the rule the flow must follow next. Each is both "
            "<b>named and signed</b>, not merely labelled: the reconstruction "
            "test split on exactly this point, and the answer is both. Not free "
            "text either — <code>consent:{kind}</code> is a catalogued family, "
            "open in its parameters and closed in its leaves.",
            "<b>The information type is a real, shared list.</b> 116 namespace "
            "families across 9 components, generated into "
            "<code>namespace_registry.json</code>. When a field on screen is "
            "“named by its family”, that is the list it is named from. Nobody has "
            "to invent categories.",
            "<b>Taking it back is a verb, not a support ticket.</b> The wire "
            "surface is 1+4: <code>scores</code> plus <code>delegates_to</code>, "
            "<code>supersedes</code>, <code>withdraws</code>, <code>recants</code>. "
            "Two of those four exist purely so a claim can be undone. The surface "
            "is conformance-frozen — changing those bytes is a defect, not an edit.",
        ],
    },
    {
        "h": "Nobody is in charge, and that is structural",
        "body": [
            "There is no admin. “Any operator can create an equally valid root, "
            "and every client chooses which roots to trust. The shipped root is a "
            "default, not the root.” Peers find each other through signed records "
            "rather than app stores, package registries or domain names.",
            "Standing is <b>earned</b>: non-transferable credits for contributions "
            "that are attributable and costly to fake — “not bought and not "
            "mined”. There is no tier to buy and none to be promoted into.",
            "<b>For the UI:</b> two people can open the same screen and correctly "
            "see different things, and neither is being restricted. So the "
            "permission idiom is wrong here. “You don't have access” describes a "
            "hierarchy that does not exist.",
        ],
    },
    {
        "h": "Safety is a floor, not a feature",
        "body": [
            "There is a halt “no score, no argument, and no attacker can "
            "override”, held by <b>2-of-3 named humans on a live quorum</b> so it "
            "still works if the roster is attacked or comms are jammed. When two "
            "principles genuinely conflict, the system does not resolve it "
            "cleverly — it stops and asks a trusted human.",
            "And when something breaks: “it fails in the record, under its own "
            "name.”",
            "<b>For the UI:</b> the halt and the deferral are surfaces a person "
            "must be able to find under stress, not settings. An error state is "
            "not a failure of the design — telling the truth about uncertainty is "
            "one of the six principles, which is why every surface owes four "
            "states and why <i>error</i> must never look like <i>empty</i>.",
        ],
    },
]

#: The designer read only the child's version and hit these walls. Each one is
#: a real mechanism that simply had not been named to them.
ANSWERED = [
    ("“The group a thing belongs to” — what is the real list?",
     "116 namespace families, 9 owning components (8 normative), generated into "
     "<code>manifests/namespace_registry.json</code> at CC 1.0-rc5. It is the "
     "shared taxonomy every tag component depends on. Do not invent categories."),
    ("“A note with your own name on it” — what does add-a-contact accept?",
     "A federation identity (fed-ID) and its fedcode — self-minted, signed, no "
     "registry involved. The client already has the surface: My Identity mints "
     "this device's fed-ID, and enrolment takes a pasted or scanned fedcode."),
    ("“Stickers you cannot hand to anyone” — is unforgeability visible?",
     "It is Proof of Benefit: non-transferable earned credits. Remove every "
     "transfer and purchase affordance. Verification is the network's job, not a "
     "step to put in front of a person."),
    ("“The promise about where it may go next” — vocabulary or free text?",
     "A controlled vocabulary. The transmission principle is named by a "
     "catalogued <code>consent:{kind}</code> leaf — retain, share, analyze, "
     "train, publish. A free-text box here would be a bug."),
    ("“No promise beats another” — is there anything positive to design?",
     "Yes. The designer's brief came out entirely negative — don't rank them, "
     "don't trade them off in copy — because nobody had named the mechanism. It "
     "is <b>Wisdom-Based Deferral</b>: the moment the system stops and hands the "
     "conflict to a human, with the reasoning attached. That is a screen, not an "
     "absence. Who receives it is the next answer."),
    ("How many circles are there, and can people make more?",
     "Five, fixed — folded down from seven in CEG 0.6. They are not "
     "user-created. Each is a hub showing Identities, Trust and Policies at that "
     "scope."),
    ("\u201cIt stops and asks a grown-up it trusts\u201d — who is that, exactly?",
     "The <b>Wise Authority</b>, and this client already has the surface: "
     "<i>Wise Authority</i>, under Node. Two distinct things share the "
     "initials — <b>Wisdom-Based Deferral (WBD)</b> is the moment of handing a "
     "conflict over; the <b>Wise Authority</b> is who adjudicates it, and "
     "rulings are logged durably in the Wisdom Bank Database. Separately, the "
     "safety halt is held by 2-of-3 named humans on a live quorum. So \u201cno "
     "principle beats another\u201d does have a positive expression on screen: the "
     "deferral, the adjudicator, and the record."),
    ("How does someone take their yes back, in the machine's own terms?",
     "Three things together, none of which is a support ticket. The envelope "
     "carries <code>subject_key_ids</code>, so the person a claim is ABOUT holds "
     "authority over it and not only the person who made it. Two of the four "
     "lifecycle verbs — <code>withdraws</code> and <code>recants</code> — exist "
     "purely to undo a claim. And the <code>consent:</code> family carries the "
     "leaves that close a consent's lifecycle. Design the door; the wire already "
     "has the hinge."),
    ("What do the audit screens actually show?",
     "An agent's own reasoning trace, carried envelope-native "
     "(<code>trace:complete:v1</code>), post-scrub. They are Integrity made "
     "visible — “transparent, auditable reasoning” — which is why they cannot "
     "be tidied away for looking like developer tooling."),
]
