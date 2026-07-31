package dev.ludex.pack

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app's tokenizer, checked **against the tokenizer that built the index**.
 *
 * Parity is not cosmetic and it is not something to reason about from the documentation.
 * The index was built by `unicode61 remove_diacritics 2`, and any divergence here produces
 * a query term that cannot match text the index holds — silently, and only for the words
 * where the two disagree, which is the hardest kind of retrieval bug to notice. So the
 * oracle is SQLite itself: every case below asks the real tokenizer what it produced and
 * requires this one to agree.
 */
class TokenizerTest {

    /** What `unicode61 remove_diacritics 2` actually stores for [text]. */
    private fun indexTokens(text: String): List<String> =
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { s ->
                s.execute(
                    "CREATE VIRTUAL TABLE t USING fts5(x, " +
                        "tokenize='unicode61 remove_diacritics 2')",
                )
                s.execute("CREATE VIRTUAL TABLE v USING fts5vocab(t, 'row')")
            }
            c.prepareStatement("INSERT INTO t(x) VALUES (?)").use {
                it.setString(1, text)
                it.executeUpdate()
            }
            c.createStatement().use { s ->
                s.executeQuery("SELECT term FROM v ORDER BY term").use { rows ->
                    val terms = mutableListOf<String>()
                    while (rows.next()) terms += rows.getString(1)
                    terms
                }
            }
        }

    /**
     * Compared as *distinct* terms, because `fts5vocab` reports the vocabulary rather
     * than the token stream: `D&D` indexes two `d` tokens and the view lists one. What is
     * under test is which terms exist, not how often.
     */
    private fun assertAgrees(text: String, note: String = "") {
        assertEquals(
            indexTokens(text).distinct().sorted(),
            Tokenizer.tokenize(text).distinct().sorted(),
            "disagreed on '$text'${if (note.isEmpty()) "" else " -- $note"}",
        )
    }

    @Test
    fun `latin diacritics are folded away, exactly as the index folds them`() {
        assertAgrees("café")
        assertAgrees("naïve")
        assertAgrees("Åsa")
    }

    @Test
    fun `non-latin diacritics are kept, because the index keeps them`() {
        // `remove_diacritics 2` is not "strip every combining mark". SQLite leaves the
        // Greek tonos in place, so stripping it here rewrote an exact query for indexed
        // text into one that matches no token in the index.
        assertAgrees("άλφα", "Greek tonos")
        assertTrue(
            Tokenizer.tokenize("άλφα").single().contains('ά'),
            "the tonos must survive: ${Tokenizer.tokenize("άλφα")}",
        )
    }

    @Test
    fun `every category the index treats as a token character is one here too`() {
        // isLetterOrDigit is narrower than unicode61, and the gap is not exotic: the
        // Roman numeral, the superscript, and every private-use glyph are all indexed.
        assertAgrees("Ⅳ", "Nl, a letter number")
        assertAgrees("²", "No, an other number")
        assertAgrees("", "Co, private use")
    }

    @Test
    fun `a supplementary-plane letter is one token, not two rejected surrogates`() {
        assertAgrees("𐌀", "Old Italic")
    }

    @Test
    fun `the ordinary cases still agree`() {
        assertAgrees("2d6damage", "digits are token characters")
        assertAgrees("fast-cast")
        assertAgrees("D&D")
        assertAgrees("The Warden sets Difficulty before dice are rolled")
    }

    @Test
    fun `an alias is stored in the form the rewriter looks it up by`() {
        assertEquals("fast cast", Tokenizer.indexForm("fast-cast"))
        assertEquals("d d", Tokenizer.indexForm("D&D"))
        assertEquals("", Tokenizer.indexForm("!!!"))
    }
}
