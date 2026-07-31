package dev.ludex.pack

/** One thing the pack's builder dropped, or could not check, and said so. */
data class BuildNote(
    val severity: String,
    val subjectKind: String,
    val subjectId: String?,
    val validation: String,
    val detail: String?,
)

/**
 * What a pack says about **its own trustworthiness**.
 *
 * The builder writes `build_report` on every build: what it dropped, and what it shipped
 * without checking. Nothing at runtime read it. So a pack whose model-written summaries
 * were never adjudicated — the `unchecked` severity, which the builder emits only when an
 * operator asked for it explicitly — installed, activated, and rendered its prose with
 * well-formed citation chips, and the only person who ever saw the warning was whoever ran
 * the build. The person holding the pack was not told.
 *
 * That is the one gap in the activation gate's theory that the gate itself cannot close.
 * Everything else the gate checks is a property it can *verify*; this is a claim the
 * builder makes about work the app cannot redo — it has no frontier judge and no source
 * document. What it can do is carry the claim forward to the surface, which is the whole
 * of what this reader is for.
 */
object BuildReport {

    /**
     * Severities in descending order of how much they should interrupt someone.
     *
     * `unchecked` outranks `dropped` deliberately. A dropped item is absent, and absence is
     * self-announcing — the roll control never appears, the summary is not there. Unchecked
     * content is *present and indistinguishable from checked content*, which is the failure
     * mode this whole product is built against.
     */
    val SEVERITIES: List<String> = listOf("unchecked", "dropped", "note")

    /**
     * The pack's own report, worst first. Empty when it kept nothing back.
     *
     * **Throws rather than returning empty** when the table cannot be read as the format
     * declares it. Swallowing the failure made a malformed `build_report` indistinguishable
     * from a clean build — so a pack could hide an `unchecked` warning simply by shipping a
     * table the reader chokes on, which is the one thing this reader exists to prevent. The
     * activation gate checks the shape (see `PackValidator`), so by the time this runs on
     * an installed pack the columns are known good; the throw is what keeps that true
     * rather than assumed.
     */
    fun of(db: Db): List<BuildNote> = db.map(
        "SELECT severity, subject_kind, subject_id, validation, detail FROM build_report",
    ) {
        BuildNote(it.string(0), it.string(1), it.stringOrNull(2), it.string(3), it.stringOrNull(4))
    }.sortedBy { SEVERITIES.indexOf(it.severity).takeIf { rank -> rank >= 0 } ?: SEVERITIES.size }

    /**
     * True when the pack admits it ships content nobody adjudicated.
     *
     * The one severity a user needs before they decide whether to activate, because it is
     * the one whose consequence is text on screen that looks exactly like text that was
     * checked.
     */
    fun shipsUnchecked(notes: List<BuildNote>): Boolean = notes.any { it.severity == "unchecked" }
}
