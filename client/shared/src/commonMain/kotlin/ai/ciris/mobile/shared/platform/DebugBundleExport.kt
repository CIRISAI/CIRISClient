package ai.ciris.mobile.shared.platform

/**
 * Write a debug bundle somewhere the user can actually reach it, and return a
 * human-readable description of where it went (or null if it could not be
 * written).
 *
 * Deliberately "save", not "share". Two of the three call sites are screens the
 * user is stuck on — a login that cannot exchange a token, a startup that never
 * completes — and on those screens a share sheet can itself fail or be
 * unavailable. A file on disk with a path we can name is the thing a user can
 * still get to us over any channel.
 *
 * The returned string is shown verbatim, so it must be a path or a location a
 * person can act on, not a status word.
 */
expect fun saveDebugBundle(fileName: String, content: String): String?

/**
 * Put the bundle on the clipboard. Separate from [saveDebugBundle] because on a
 * phone that cannot write shared storage, copy-and-paste into a chat is the only
 * remaining route out.
 */
expect fun copyToClipboard(text: String): Boolean

/**
 * Save a copy of a file's bytes somewhere the person can open it with their
 * own apps, and return where, in words they can act on ("Downloads/report.pdf"),
 * or null if it could not be written.
 *
 * The same locations as [saveDebugBundle], for the same reason: a place the
 * person can find again. Used by Files for anything the app does not preview
 * itself. Nothing here overwrites: an existing name gets a numbered sibling.
 */
expect fun saveFileCopy(fileName: String, mediaType: String, bytes: ByteArray): String?

/**
 * Whether this app can hand a file to the system share sheet (CIRISClient#249).
 * Android only, and only where the app declares the logs FileProvider
 * (`<package>.logs`, CIRISAgent 2.17.0's manifest); false everywhere else, so
 * Share is hidden there and Download still works.
 */
expect fun canShareFiles(): Boolean

/** Offer [bytes] as [fileName] to the share sheet. False when this app cannot. */
expect fun shareFile(fileName: String, mediaType: String, bytes: ByteArray): Boolean

/** One text entry of a zip (the log download's MANIFEST.txt), or null: absent, not a zip, or no unzip here. */
expect fun zipTextEntry(zip: ByteArray, name: String): String?
