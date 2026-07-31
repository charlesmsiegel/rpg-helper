package dev.ludex.retrieval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueryNormalizerTest {

    @Test
    fun `case, whitespace, and edges are folded`() {
        assertEquals(
            "how does grappling work",
            QueryNormalizer.normalize("  How  Does\tGrappling\n work  "),
        )
    }

    @Test
    fun `a phone keyboard's punctuation matches a book's`() {
        // A keyboard produces the curly forms and a book is set with the straight ones, so
        // without this a query for a creature's speed misses text that says exactly that.
        assertEquals("a creature's speed", QueryNormalizer.normalize("a creature’s speed"))
        assertEquals("levels 1-5", QueryNormalizer.normalize("levels 1–5"))
        assertEquals("\"unseen\" servant", QueryNormalizer.normalize("“unseen” servant"))
    }

    @Test
    fun `composed and decomposed forms normalize alike`() {
        // NFC first, so a query typed one way matches text stored the other.
        assertEquals(
            QueryNormalizer.normalize("café"),
            QueryNormalizer.normalize("café"),
        )
    }

    @Test
    fun `an empty query stays empty`() {
        assertEquals("", QueryNormalizer.normalize("   \t\n "))
    }

    // ---------------------------------------------------------------- the rewrite check

    @Test
    fun `a self-contained question never wakes the model`() {
        // However it is phrased. Keeping the model off the common path is the guarantee.
        for (query in listOf(
            "how does grappling work",
            "what is the underdark",
            "ogre statblock",
            "does a restrained creature move",
            "andiron",
            "thatch",
        )) {
            assertFalse(
                QueryNormalizer.needsRewrite(query, conversationTurns = 3),
                "'$query' is answerable alone",
            )
        }
    }

    @Test
    fun `a pronoun or an ellipsis with a conversation behind it does`() {
        for (query in listOf(
            "what about at level 5?",
            "how about undead",
            "and if they resist",
            "it doesn't say",
            "those rules",
            "they cannot",
        )) {
            assertTrue(
                QueryNormalizer.needsRewrite(query, conversationTurns = 2),
                "'$query' cannot be resolved alone",
            )
        }
    }

    @Test
    fun `a leading ellipsis is read before normalization folds it away`() {
        // Normalization maps the ellipsis to whitespace, so checking the normalized form
        // turns "...for wizards?" into "for wizards?" -- a query matching no opener, sent
        // to retrieval having lost the thing it was about.
        assertTrue(QueryNormalizer.needsRewrite("\u2026for wizards?", conversationTurns = 2))
        assertTrue(QueryNormalizer.needsRewrite("...for wizards?", conversationTurns = 2))
        assertFalse(QueryNormalizer.needsRewrite("\u2026for wizards?", conversationTurns = 0))
    }

    @Test
    fun `a demonstrative that is not the opener does not trigger the check`() {
        // "Nothing else triggers it." The check is cheap and deliberately narrow: a query
        // mentioning "that" mid-sentence usually names its own subject, and widening the
        // rule to catch it would put the model on the common path to save a rare miss.
        assertFalse(QueryNormalizer.needsRewrite("does that stack", conversationTurns = 2))
        assertFalse(QueryNormalizer.needsRewrite("can a grapple end this turn", 2))
    }

    @Test
    fun `with no conversation there is nothing to resolve to`() {
        // Rewriting here would be the model inventing a subject, which is worse than
        // searching for the words the user actually typed.
        assertFalse(QueryNormalizer.needsRewrite("what about at level 5?", conversationTurns = 0))
        assertFalse(QueryNormalizer.needsRewrite("it doesn't say", conversationTurns = 0))
    }
}
