package ai.ciris.mobile.shared.models.chat

/**
 * HOW ONE TRANSCRIPT ENTRY SHOULD RENDER (CIRISClient#37).
 *
 * The server stopped returning a message list and started returning entries on
 * TWO AXES: [CegChatMessage.kind] (what the entry is) and
 * [CegChatMessage.authorKind] (what the author is), with
 * [CegChatMessage.relation] as the viewer-dependent convenience derived from
 * them. The flat `self | other_human | my_agent | other_agent | system | error`
 * enum was considered upstream and rejected, because a channel breaks it: the
 * same row is `my_agent` to one member and `other_agent` to another, and these
 * rows replicate BYTE-IDENTICALLY to every member. A viewer-dependent value
 * cannot be a property of the row.
 *
 * This file exists so that distinction survives contact with a UI. The decision
 * is a pure function over the entry, exhaustive over [Presentation], so:
 *
 *   * every combination is enumerable and testable without composing anything
 *   * a new `kind` or `author_kind` fails COMPILATION at the `when` rather than
 *     falling into whatever branch happens to be last
 *
 * That is the same reason `BackendNotice.noticeFor` is shaped this way: a rule
 * that can be read but not run gets read wrong.
 */
sealed interface Presentation {

    /** A person's words, aligned to the viewer's own side. */
    data object OwnMessage : Presentation

    /** Another person's words. [CegChatMessage.speakerKeyId] names them. */
    data object OtherPersonMessage : Presentation

    /**
     * An agent this viewer OWNS, speaking.
     *
     * Deliberately not [OwnMessage]. Same owner is not the same speaker, and
     * rendering a machine's words as the user's own attributes them to a human —
     * the single most consequential confusion available in a transcript.
     */
    data object OwnAgentMessage : Presentation

    /** Somebody else's agent. */
    data object OtherAgentMessage : Presentation

    /**
     * Directory resolution has not answered yet.
     *
     * A real state, not a bug, and NOT a fallback to `person`: the key body is
     * still in flight and will resolve later. Render neutrally and re-resolve.
     * Guessing `person` here is the absence-as-negative mistake that produced
     * CIRISClient#21 and #34.
     */
    data object UnresolvedAuthorMessage : Presentation

    /**
     * A centred note in the transcript, IN ORDER — not a toast, not a dialog.
     *
     * When the key exchange completed is part of the conversation's story, and
     * lifting it out of sequence loses that.
     */
    data object SystemNote : Presentation

    /** A note that reads as a problem. Same placement, different weight. */
    data object ErrorNote : Presentation
}

/**
 * The one place the axes are collapsed into a rendering.
 *
 * Order matters and is not arbitrary: [CegChatMessage.kind] is asked FIRST,
 * because a system entry has no author to reason about — `author` is empty on
 * one by definition, and asking about author kind first would send every system
 * note down the unresolved-author branch.
 */
fun presentationOf(entry: CegChatMessage): Presentation = when (entry.kind) {
    CegChatMessage.KIND_SYSTEM -> Presentation.SystemNote
    CegChatMessage.KIND_ERROR -> Presentation.ErrorNote
    else -> presentationOfMessage(entry)
}

private fun presentationOfMessage(entry: CegChatMessage): Presentation = when {
    // An agent is checked BEFORE `mine`, because an own-agent row can carry
    // mine=true (same owner) and must still never render as the viewer speaking.
    entry.authorKind == CegChatMessage.AUTHOR_KIND_AGENT ->
        if (entry.relation == CegChatMessage.RELATION_OWN_AGENT) Presentation.OwnAgentMessage
        else Presentation.OtherAgentMessage

    entry.relation == CegChatMessage.RELATION_SELF || entry.mine -> Presentation.OwnMessage

    // Unknown is asked LAST among authored rows: a row whose author is still
    // resolving but which the server already told us is OURS is ours, and
    // showing our own message as an unresolved stranger would be worse than
    // either answer.
    entry.authorKind == CegChatMessage.AUTHOR_KIND_UNKNOWN -> Presentation.UnresolvedAuthorMessage

    else -> Presentation.OtherPersonMessage
}

/**
 * The text to show for an entry, given a localizer.
 *
 * [CegChatMessage.messageId] is a localization key and [CegChatMessage.body]
 * carries the server's English for it. Look the key up, and fall back to the
 * body — NEVER to a blank line, and never to the raw key. A missing translation
 * is a worse-quality sentence; a blank line is a transcript that lost an event.
 *
 * This is the same lesson as CIRISClient#34, where a raw dotted key reached a
 * user because a cascade returned early.
 */
fun chatEntryText(entry: CegChatMessage, localize: (String) -> String?): String {
    val id = entry.messageId
    if (id != null) {
        val translated = localize(id)
        // A localizer that echoes the key back has NOT translated it. Treating
        // that as a hit is precisely how `mobile.login_setup_complete_relogin`
        // reached a user's screen.
        if (!translated.isNullOrBlank() && translated != id) return translated
    }
    return entry.body.ifBlank { entry.unopenedReason ?: "" }
}
