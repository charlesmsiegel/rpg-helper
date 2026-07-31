package dev.rpghelper.retrieval

import dev.rpghelper.pack.PackForge
import dev.rpghelper.pack.exec
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LexicalTest {

    private val directory: Path = Files.createTempDirectory("retrieval")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    // ---------------------------------------------------------------- escaping

    @Test
    fun `every FTS5 metacharacter survives as a literal`() {
        // Each of these is syntax to FTS5 and ordinary vocabulary in a game book.
        for (term in listOf("D&D", "Ars Magica: The Divine", "fast-cast", "(paren", "star*", "^caret")) {
            val built = LexicalQuery.build(listOf(term))!!
            assertTrue(built.startsWith("\"") && built.endsWith("\""), "'$term' must be quoted")
            assertMatchesWithoutError(built)
        }
    }

    @Test
    fun `an operator used as a term cannot restructure the boolean`() {
        // The dangerous case: a canonical term containing NOT would turn a widening
        // expansion into an exclusion, returning *fewer* results than no expansion at all.
        val built = LexicalQuery.build(listOf("grapple", "NOT", "OR", "NEAR", "AND"))!!
        assertEquals(
            "\"grapple\" OR \"NOT\" OR \"OR\" OR \"NEAR\" OR \"AND\"",
            built,
            "operators appear only as quoted literals, never as syntax",
        )
        assertMatchesWithoutError(built)
    }

    @Test
    fun `an embedded double quote is doubled, not escaped away`() {
        val built = LexicalQuery.build(listOf("""the "unseen" servant"""))!!
        assertEquals("\"the \"\"unseen\"\" servant\"", built)
        assertMatchesWithoutError(built)
    }

    @Test
    fun `a query with no usable terms produces no expression at all`() {
        // An empty MATCH is a syntax error, and a query of pure punctuation is a query
        // with no terms rather than a query for everything.
        assertNull(LexicalQuery.build(emptyList()))
        assertNull(LexicalQuery.build(listOf("", "   ")))
    }

    @Test
    fun `terms are capped and deduplicated`() {
        val many = (1..100).map { "term$it" }
        val built = LexicalQuery.build(many)!!
        assertEquals(LexicalQuery.MAX_TERMS, built.split(" OR ").size)

        assertEquals("\"grapple\"", LexicalQuery.build(listOf("grapple", "grapple"))!!)
    }

    /** Runs the expression against a real FTS5 index; a syntax error would throw. */
    private fun assertMatchesWithoutError(expression: String) {
        val pack = PackForge.writePack(directory)
        DriverManager.getConnection("jdbc:sqlite:${pack.toAbsolutePath()}").use { c ->
            c.createStatement().use { s ->
                s.executeQuery(
                    "SELECT count(*) FROM chunks_fts WHERE chunks_fts MATCH '" +
                        expression.replace("'", "''") + "'",
                ).use { it.next() }
            }
        }
    }

    // ---------------------------------------------------------------- tokenizer parity

    @Test
    fun `tokenization matches what the index actually holds`() {
        // Parity is the whole point: a divergence produces a term that cannot match text
        // the index contains, silently, and only for the words where the two disagree.
        val samples = listOf(
            "Grappling. To grapple a creature",
            "2d6damage and 4 rounds",
            "Café au lait",
            "hyphenated-word, punctuated!",
            "MiXeD CaSe",
        )
        val pack = PackForge.writePack(directory) { connection ->
            connection.exec("DELETE FROM chunks_fts")
            samples.forEachIndexed { index, text ->
                connection.exec(
                    "INSERT INTO chunks_fts (rowid, text, heading_path) " +
                        "VALUES (${index + 1}, '${text.replace("'", "''")}', '')",
                )
            }
        }

        DriverManager.getConnection("jdbc:sqlite:${pack.toAbsolutePath()}").use { c ->
            for ((index, text) in samples.withIndex()) {
                for (token in Tokenizer.tokenize(text)) {
                    assertTrue(
                        matchesRow(c, token, index + 1L),
                        "token '$token' from \"$text\" is not in the index",
                    )
                }
            }
        }
    }

    private fun matchesRow(connection: Connection, token: String, rowid: Long): Boolean {
        connection.createStatement().use { s ->
            s.executeQuery(
                "SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH " +
                    "'${LexicalQuery.literal(token).replace("'", "''")}'",
            ).use { results ->
                while (results.next()) if (results.getLong(1) == rowid) return true
            }
        }
        return false
    }

    @Test
    fun `digits are token characters, so a mixed run is one token`() {
        assertEquals(listOf("2d6damage", "and", "4", "rounds"), Tokenizer.tokenize("2d6damage and 4 rounds"))
    }

    @Test
    fun `diacritics are folded and case is lowered`() {
        assertEquals(listOf("cafe", "au", "lait"), Tokenizer.tokenize("Café au lait"))
        assertEquals(listOf("vass"), Tokenizer.tokenize("Váss"))
    }

    @Test
    fun `punctuation splits tokens`() {
        assertEquals(listOf("fast", "cast"), Tokenizer.tokenize("fast-cast"))
        assertEquals(listOf("d", "d"), Tokenizer.tokenize("D&D"))
    }

    // ---------------------------------------------------------------- alias rewrite

    private val aliases = listOf(
        Alias("core", "wrestle", "grapple", 1L),
        Alias("core", "wrestling", "grapple", 1L),
        Alias("core", "blade of the fallen", "sunblade", 2L),
        Alias("core", "fallen", "wraith", 3L),
        Alias("core", "under dark", "underdark", null),
    )

    @Test
    fun `an alias adds its canonical without dropping the user's wording`() {
        // Expansion only ever widens. An alias that fires wrongly costs precision; a
        // rewrite that replaces the user's wording costs the query.
        val result = AliasRewriter(aliases).rewrite("how do I wrestle a goblin")
        assertTrue("wrestle" in result.terms, "the user's own token survives")
        assertTrue("grapple" in result.terms, "the canonical is added")
        assertTrue("goblin" in result.terms)
    }

    @Test
    fun `matching is greedy, longest-first, and non-overlapping`() {
        // Without this, "blade of the fallen" fires as itself and again as "fallen",
        // weighting one concept twice and pulling in an unrelated canonical.
        val result = AliasRewriter(aliases).rewrite("the blade of the fallen")
        assertEquals(listOf("blade of the fallen"), result.matched.map { it.alias })
        assertTrue("sunblade" in result.terms)
        assertTrue("wraith" !in result.terms, "the shorter alias inside it must not also fire")
    }

    @Test
    fun `a multi-word alias matches across tokenization`() {
        val result = AliasRewriter(aliases).rewrite("what lives in the Under Dark")
        assertTrue("underdark" in result.terms)
    }

    @Test
    fun `an alias with a chunk contributes an entity hit`() {
        val result = AliasRewriter(aliases).rewrite("wrestle")
        assertEquals(setOf("core" to 1L), result.entityHits)
    }

    @Test
    fun `an alias with no chunk contributes no entity hit`() {
        // A ranking signal needs something to rank; a NULL chunk_id simply means the term
        // is not governed by a particular passage.
        val result = AliasRewriter(aliases).rewrite("under dark")
        assertTrue(result.entityHits.isEmpty())
        assertTrue("underdark" in result.terms, "it still widens the query")
    }

    @Test
    fun `an unmatched query is returned unchanged`() {
        val result = AliasRewriter(aliases).rewrite("how does falling damage work")
        assertEquals(Tokenizer.tokenize("how does falling damage work"), result.terms)
        assertTrue(result.matched.isEmpty())
    }

    @Test
    fun `aliases match in folded form`() {
        // Packs store aliases folded and activation rejects any that are not, so matching
        // is an indexed lookup rather than a fold-on-the-fly comparison.
        val result = AliasRewriter(aliases).rewrite("How Do I WRESTLE")
        assertTrue("grapple" in result.terms)
    }
}
