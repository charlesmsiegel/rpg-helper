package dev.ludex.session

import dev.ludex.pack.BuildNote
import dev.ludex.pack.BuildReport
import dev.ludex.pack.Sqlite
import dev.ludex.pack.map
import dev.ludex.state.InstalledPack
import dev.ludex.state.PackLibrary

/**
 * One correction an installed pack makes, rendered from **the snapshot it carries**.
 *
 * `06-ui-spec.md` §2.2: an errata pack whose target is not installed still has to explain
 * itself — *replaces Combat > Grappling, Other Core Rulebook 1e, pp. 40–41*. Resolving the
 * target through the pack it points at would leave the notice blank in precisely the case
 * it matters most, a user reading a correction without owning the book being corrected. So
 * every field here comes out of the `supersessions` row itself, which is why the format
 * carries them.
 *
 * @param inEffect whether this correction is actually withdrawing anything right now. A
 * supersession whose target is not installed or not active is real and does nothing, and
 * the surface has to be able to say which.
 */
data class SupersessionNotice(
    val packUid: String,
    val targetSourceUid: String,
    val targetStableKey: String,
    val targetTitle: String,
    val targetEdition: String?,
    val targetHeadingPath: String?,
    val pageLabelStart: String?,
    val pageLabelEnd: String?,
    val inEffect: Boolean,
) {
    /** The one-line rendering the spec names, built from the snapshot and nothing else. */
    fun render(): String = buildString {
        append("replaces ")
        append(targetHeadingPath ?: targetStableKey)
        append(", ").append(targetTitle)
        targetEdition?.let { append(" ").append(it) }
        val pages = listOfNotNull(pageLabelStart, pageLabelEnd).distinct()
        if (pages.isNotEmpty()) {
            append(", ").append(if (pages.size > 1) "pp. " else "p. ").append(pages.joinToString("–"))
        }
    }
}

/** Everything the Packs surface shows about one installed pack. */
data class ShelvedPack(
    val pack: InstalledPack,
    /** The pack's own account of what it dropped or shipped unchecked. Worst first. */
    val buildNotes: List<BuildNote>,
    /** Corrections this pack makes, whether or not they are currently in effect. */
    val supersessions: List<SupersessionNotice>,
    /**
     * Non-null when the pack's own report could not be read.
     *
     * [BuildReport.of] throws rather than returning empty, because a malformed report and a
     * clean build must not look alike — a pack could otherwise hide an `unchecked` warning
     * by shipping a table the reader chokes on. Carried as a field so one damaged pack does
     * not take the whole list down with it.
     */
    val unreadable: String? = null,
) {
    /** True when the pack admits it ships content nobody adjudicated. */
    val shipsUnchecked: Boolean get() = BuildReport.shipsUnchecked(buildNotes)
}

/**
 * Reads what a Packs surface shows, without opening the active set.
 *
 * Separate from [Library] on purpose: that opens the *active* packs under leases, for
 * answering questions, and this has to describe **inactive** ones too — a user deciding
 * whether to activate a pack needs its build report before it is live, which is exactly
 * when `Library` will not have it open.
 *
 * Each pack is opened read-only, read, and closed. That is more work than holding them all
 * open, and it is the right trade for a surface a user visits occasionally: an open handle
 * per installed pack, held for the life of a screen, is a file that cannot be uninstalled
 * and a reader count that does not fall.
 */
object Shelf {

    /**
     * Every installed pack, in priority order, with what the surface needs about each.
     *
     * A pack whose file is missing or damaged still appears, with [ShelvedPack.unreadable]
     * set — dropping it from the list would remove the only row from which a user could
     * uninstall the thing that is broken.
     */
    fun list(library: PackLibrary): List<ShelvedPack> {
        val active = library.active()
        val activeUids = active.map { it.packUid }.toSet()
        // What the active set actually supplies, as `(source_uid, stable_key)` -- the exact
        // coordinate a supersession targets, not just the book.
        //
        // The book alone was not enough. An errata row whose `target_stable_key` is stale or
        // misspelled points at a passage that no longer exists, `Supersession.compute`
        // withdraws nothing for it, and the surface said it was replacing a rule anyway --
        // telling a user a correction is in force while the uncorrected text is what every
        // answer will quote. That is the same failure as a filter failing open, arriving
        // through a label.
        val activeTargets = active.flatMapTo(mutableSetOf<Pair<String, String>>()) { pack ->
            runCatching {
                Sqlite.openReadOnly(library.fileOf(pack.installId)).use { db ->
                    db.map(
                        "SELECT s.source_uid, c.stable_key FROM chunks c " +
                            "JOIN sources s ON s.source_id = c.source_id " +
                            "WHERE c.stable_key IS NOT NULL",
                    ) { it.string(0) to it.string(1) }
                }
            }.getOrDefault(emptyList())
        }

        return library.installed().map { pack ->
            runCatching {
                Sqlite.openReadOnly(library.fileOf(pack.installId)).use { db ->
                    ShelvedPack(
                        pack = pack,
                        buildNotes = BuildReport.of(db),
                        supersessions = noticesIn(
                            db,
                            pack.packUid,
                            live = pack.packUid in activeUids,
                            activeTargets = activeTargets,
                        ),
                    )
                }
            }.getOrElse {
                ShelvedPack(pack, emptyList(), emptyList(), unreadable = it.message ?: "unreadable")
            }
        }
    }

    /**
     * The corrections one pack declares.
     *
     * `in_effect` is decided by whether the **targeted book** is active, not by whether
     * this pack is: an erratum that names a source no active pack supplies is a correction
     * with nothing to correct, and telling a user it is in force would be telling them a
     * rule was withdrawn when it never was.
     */
    private fun noticesIn(
        db: dev.ludex.pack.Db,
        packUid: String,
        live: Boolean,
        activeTargets: Set<Pair<String, String>>,
    ): List<SupersessionNotice> = db.map(
        "SELECT target_source_uid, target_stable_key, target_title, target_edition, " +
            "target_heading_path, target_page_label_start, target_page_label_end " +
            "FROM supersessions ORDER BY supersession_id",
    ) { row ->
        SupersessionNotice(
            packUid = packUid,
            targetSourceUid = row.string(0),
            targetStableKey = row.string(1),
            targetTitle = row.string(2),
            targetEdition = row.stringOrNull(3),
            targetHeadingPath = row.stringOrNull(4),
            pageLabelStart = row.stringOrNull(5),
            pageLabelEnd = row.stringOrNull(6),
            // Three things, and they are three different questions: this pack must be
            // active for its corrections to apply at all; some active pack must supply the
            // book being corrected; and that book must actually contain the passage this
            // row names. An erratum over a book nobody installed withdraws nothing, and one
            // whose key no longer matches anything withdraws nothing either -- and in both
            // cases saying "in effect" tells the user a rule was removed while the
            // uncorrected text is what every answer still quotes.
            inEffect = live && (row.string(0) to row.string(1)) in activeTargets,
        )
    }
}
