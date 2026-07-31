package dev.ludex.pack

import java.nio.file.Path

/**
 * **The** SQLite binding this program reads packs with, chosen once.
 *
 * There are two implementations of [Db] and only one of them is a binding a product can
 * ship: `sqlite-jdbc` carries native desktop libraries that do not load on Android, and
 * `:app` excludes the artifact outright. [BundledDb] carries its own SQLite build and runs
 * on both, so it is the binding — not the "Android binding".
 *
 * This object exists because the choice was previously made at each of eight call sites,
 * every one of them naming `JdbcDb`. Each was correct on a desktop and each was a crash on
 * a phone, and nothing in the code said which platform it was written for. Making the
 * choice in one place is what turns "the device build happens to work" into something the
 * code states. `NoDesktopBindingInProductionTest` holds it: no `src/main` source outside
 * this file may name `JdbcDb`.
 *
 * `JdbcDb` remains, as an **independently written second implementation** rather than as a
 * fallback. Its whole job now is to disagree with this one in `BundledDbTest` if either
 * ever stops behaving like SQLite.
 */
object Sqlite {

    /**
     * Opens [path] read-only.
     *
     * @throws IllegalArgumentException if [path] is not a regular file
     * @throws PackReadException if it cannot be opened as a database
     */
    fun openReadOnly(path: Path): Db = BundledDb.openReadOnly(path)
}
