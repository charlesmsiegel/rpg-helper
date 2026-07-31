package dev.rpghelper.model

import dev.rpghelper.pack.ChunkRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One text completion. The whole of what a runtime has to provide.
 *
 * Everything else about generation — what to ask, how to read the reply, what to do when
 * the reply is nonsense — is the same whether the weights are behind llama.cpp, MediaPipe,
 * or a socket, so it lives here and is tested here. A backend supplies bytes in and bytes
 * out; it does not get to invent its own prompt contract, because the prompt contract is
 * where the guarantees are.
 */
fun interface TextCompletion {
    /**
     * @param stop sequences at which generation should end, if the backend can honour them.
     * A backend that cannot is not broken: the parser is written to survive trailing text.
     */
    fun complete(prompt: String, maxTokens: Int, stop: List<String>): String
}

/**
 * The prompts, in one place, for the three text jobs the [Generator] interface names.
 *
 * They are written as *instructions about provenance* rather than as instructions about
 * style, because provenance is the thing this product guarantees and style is not. The
 * model is told, every time, that it may only use the passages given — and the code does
 * not rely on that instruction being obeyed: [Replies.answer] drops any citation naming a
 * chunk that was not in the context, and the claim-support harness checks the rest.
 */
object Prompts {

    /**
     * The follow-up rewrite: *"what about at level 5?"* → the question it stands for.
     *
     * Deliberately not "answer this" — the model is being used as a **pronoun resolver**,
     * so the instruction says to return one question and nothing else, and the parser takes
     * the first line either way. A rewrite that hallucinates a subject would send retrieval
     * after something the user never asked about, and the cache would then store that
     * fabrication as the resolved query.
     */
    fun normalize(query: String, history: List<Turn>): String = buildString {
        appendLine("You rewrite a follow-up question so it can be understood on its own.")
        appendLine("Replace pronouns and elliptical references using the conversation below.")
        appendLine("Add nothing that is not implied by it. Return one question and nothing else.")
        appendLine()
        appendLine("Conversation, oldest first:")
        history.takeLast(HISTORY_TURNS).forEach { turn ->
            appendLine("Q: ${turn.query.trim()}")
            appendLine("A: ${turn.answer.trim().take(CARD_EXCERPT)}")
        }
        appendLine()
        appendLine("Follow-up: ${query.trim()}")
        append("Rewritten question:")
    }

    /**
     * What the quote cards did not already answer.
     *
     * Returns the empty string when they answered it, which is how route 3 is *skipped*
     * rather than run and discarded — the difference between a question that costs a
     * database read and one that costs a multi-gigabyte model.
     */
    fun residual(query: String, covered: List<CardSummary>): String = buildString {
        appendLine("A question has been partly answered by quoting the rulebook.")
        appendLine("State only the part that remains unanswered, as one short question.")
        appendLine("If the quotations answer it entirely, reply with exactly: NONE")
        appendLine()
        appendLine("Question: ${query.trim()}")
        appendLine("Already answered by quotations from:")
        covered.forEach { appendLine("- ${it.kind}: ${it.headingPath ?: "(untitled)"}") }
        appendLine()
        append("Remaining question:")
    }

    /**
     * The setting answer, over redacted chunks only.
     *
     * **Sentences carrying a source, not prose carrying byte offsets.** Asking a model for
     * `[start, end)` offsets into its own output is asking it to count UTF-8 bytes, which it
     * cannot do — the offsets would be wrong, and a wrong offset is a chip anchored to
     * corrupted text. So the model emits sentences, each naming the passage that supports
     * it, and *this code* assembles the answer and computes the spans. Offsets are then
     * correct by construction rather than by the model's arithmetic.
     */
    fun answer(residual: String, context: List<RedactedChunk>): String = buildString {
        appendLine("Answer the question using ONLY the passages below.")
        appendLine("Do not use anything you know from elsewhere. Do not guess.")
        appendLine("If the passages do not answer it, return an empty list of sentences.")
        appendLine()
        appendLine("Reply as JSON, and nothing else:")
        appendLine("""{"sentences":[{"text":"<one sentence>","source":"<id of the passage>"}]}""")
        appendLine()
        appendLine("Passages:")
        context.forEach { chunk ->
            appendLine("--- id: ${chunk.ref.packUid}#${chunk.ref.chunkId}")
            chunk.headingPath?.let { appendLine("heading: $it") }
            appendLine(chunk.redactedText.trim())
        }
        appendLine()
        appendLine("Question: ${residual.trim()}")
        append("JSON:")
    }

    /**
     * A photographed page or object becomes a **retrieval query**, never an answer.
     *
     * The one job whose output must not reach the user as prose: `describeImage` produces
     * something to search with, and the answer comes from the books that search finds.
     */
    fun describeImage(): String =
        "Describe what this image shows, as a short search query someone would type to " +
            "look it up in a tabletop roleplaying rulebook. Return the query and nothing else."

    /** Turns kept in a rewrite prompt. Enough to resolve a pronoun; not the whole session. */
    const val HISTORY_TURNS: Int = 4

    /** Per-turn excerpt in a rewrite prompt, in characters. */
    const val CARD_EXCERPT: Int = 400
}

/**
 * Reading what came back — **defensively, because this is model output**.
 *
 * Every function here treats the reply as untrusted text of unknown shape, for the same
 * reason a pack is untrusted: it is produced elsewhere, and the failure mode of believing
 * it is a confident wrong answer rather than a crash.
 */
object Replies {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The first non-blank line, trimmed of the quoting a model likes to add. */
    fun oneLine(reply: String): String =
        reply.lineSequence()
            .map { it.trim().removeSurrounding("\"").trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()

    /** Null when the quotations answered the question — the signal that route 3 is skipped. */
    fun residual(reply: String): String? = oneLine(reply)
        .takeIf { it.isNotEmpty() && !it.equals("NONE", ignoreCase = true) }

    /**
     * Assembles the answer and its attributions from the model's sentences.
     *
     * Three things happen here that are not parsing:
     *
     * - **Spans are computed, never read.** The text is joined by this function, so every
     *   `[start, end)` is a real UTF-8 boundary of the string that will be rendered.
     * - **A source naming a chunk that was not in the context is dropped**, and its sentence
     *   is kept as unattributed text. The spec's rule is that such an attribution is a
     *   generation failure; the sentence itself is not evidence of anything worse, and
     *   discarding it silently would edit the answer.
     * - **A reply that is not JSON at all becomes one unattributed answer.** That is the
     *   defined limiting case — footer citations instead of chips, the whole context checked
     *   instead of one chunk — rather than an error path with its own behaviour.
     */
    fun answer(reply: String, context: List<RedactedChunk>): GeneratedAnswer {
        val known = context.associateBy { "${it.ref.packUid}#${it.ref.chunkId}" }
        val sentences = parseSentences(reply)
            ?: return GeneratedAnswer(reply.trim(), emptyList())

        val text = StringBuilder()
        val attributions = mutableListOf<Attribution>()
        for ((sentence, source) in sentences) {
            val trimmed = sentence.trim()
            if (trimmed.isEmpty()) continue
            if (text.isNotEmpty()) text.append(' ')
            val start = text.toString().toByteArray(Charsets.UTF_8).size
            text.append(trimmed)
            val end = text.toString().toByteArray(Charsets.UTF_8).size
            val chunk = known[source]?.ref
            if (chunk != null) attributions += Attribution(start, end, chunk)
        }
        return GeneratedAnswer(text.toString(), attributions)
    }

    /** `[(text, source)]`, or null when the reply is not the JSON that was asked for. */
    private fun parseSentences(reply: String): List<Pair<String, String?>>? = runCatching {
        // A model that wraps JSON in prose or a code fence is the ordinary case, not a
        // failure: take the outermost braces rather than refusing the whole reply.
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        json.parseToJsonElement(reply.substring(start, end + 1))
            .jsonObject["sentences"]!!
            .jsonArray
            .map { element ->
                val row = element.jsonObject
                row["text"]!!.jsonPrimitive.content to row["source"]?.jsonPrimitive?.content
            }
    }.getOrNull()
}

/**
 * A [Generator] built from one [TextCompletion] — the class every runtime actually returns.
 *
 * The three jobs stay closed, each keeps its own prompt contract, and none of that is a
 * backend's business. A runtime that wanted to answer differently would have to change this
 * file, where the rules are written down and tested, rather than in a corner of an adapter
 * nobody reviews.
 *
 * @param availability what the surfaces read. A loaded model is [Availability.Ready]; a
 * runtime that failed to load simply does not construct one of these.
 */
class PromptedGenerator(
    private val completion: TextCompletion,
    override val availability: Availability = Availability.Ready,
    private val maxAnswerTokens: Int = 512,
) : Generator {

    override fun normalize(query: String, history: List<Turn>): String {
        val rewritten = Replies.oneLine(
            completion.complete(Prompts.normalize(query, history), maxTokens = 96, stop = listOf("\n")),
        )
        // **The original wins when the rewrite is empty or absurd.** A rewrite is an
        // improvement to a query, never a precondition for one, so every failure here
        // degrades to searching what the user actually typed.
        return rewritten.takeIf { it.isNotBlank() && it.length <= MAX_QUERY } ?: query
    }

    override fun residualIntent(query: String, covered: List<CardSummary>): String? =
        Replies.residual(
            completion.complete(Prompts.residual(query, covered), maxTokens = 64, stop = listOf("\n")),
        )?.takeIf { it.length <= MAX_QUERY }

    override fun answer(residual: String, context: List<RedactedChunk>): GeneratedAnswer {
        // An empty context is not a question for the model. Routing should not get here, and
        // if it does, inventing prose from nothing is the exact failure mode to avoid.
        if (context.isEmpty()) return GeneratedAnswer("", emptyList())
        return Replies.answer(
            completion.complete(Prompts.answer(residual, context), maxAnswerTokens, stop = emptyList()),
            context,
        )
    }

    /**
     * Not supported by a text-only completion, and it says so rather than pretending.
     *
     * A multimodal runtime overrides this. Returning the empty string would send retrieval
     * an empty query and produce a refusal card that blames the books for a missing feature.
     */
    override fun describeImage(image: ImageBuffer): String =
        throw UnsupportedOperationException("this runtime is text-only; camera input needs a multimodal model")

    private companion object {
        /** A rewritten query longer than this is not a query; it is the model rambling. */
        const val MAX_QUERY = 300
    }
}
