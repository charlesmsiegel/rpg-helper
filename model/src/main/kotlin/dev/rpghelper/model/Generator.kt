package dev.rpghelper.model

/** A chunk identified the only way that is unambiguous across packs. */
data class ChunkRef(val packUid: String, val chunkId: Long)

/** One turn of the conversation window normalization may resolve against. */
data class Turn(val query: String, val answer: String)

/** What a quote or derived card already covers, for the residual-intent pass. */
data class CardSummary(val kind: String, val headingPath: String?, val summary: String)

/**
 * A `('setting', 'source')` chunk with its nested verbatim spans already excised.
 *
 * **The type is the enforcement.** That generation cannot be handed a raw chunk is not a
 * comment or a review checklist item: there is no constructor from a chunk that skips
 * redaction, so a fourth caller cannot arrive that forgets to. `redactedText` is the only
 * text on it, and there is deliberately no accessor for the original.
 */
class RedactedChunk private constructor(
    val ref: ChunkRef,
    val headingPath: String?,
    val redactedText: String,
) {
    companion object {
        /**
         * @param redactedText the parent's text with every nested verbatim child excised,
         * as `Nesting.redactedText` produces it. Null there means redaction was not safely
         * possible, and this returns null for the same reason: fail closed.
         */
        fun of(ref: ChunkRef, headingPath: String?, redactedText: String?): RedactedChunk? {
            if (redactedText == null || redactedText.isBlank()) return null
            return RedactedChunk(ref, headingPath, redactedText)
        }
    }
}

/** Images arrive as encoded bytes; the app never interprets them itself. */
class ImageBuffer(val bytes: ByteArray, val mimeType: String)

/** Audio arrives as PCM the transcriber understands. */
class AudioBuffer(val samples: ShortArray, val sampleRateHz: Int)

/** Audio to text. Separate from [Generator] so voice survives the model being absent. */
interface Transcriber {
    fun transcribe(audio: AudioBuffer): String
}

/** Where the generative model is in its lifecycle. */
sealed interface Availability {
    /** Loaded, or loadable on demand. */
    object Ready : Availability

    /** Never fetched. The user is offered the download. */
    object NotDownloaded : Availability

    data class Downloading(val fetched: Long, val total: Long) : Availability

    /**
     * Distinct from [NotDownloaded] on purpose: a user who already chose to download
     * should be offered a retry, not asked to make the same decision again.
     */
    data class Failed(val reason: String) : Availability
}

/** A model-produced span of the answer, and the chunk it claims supports it. */
data class Attribution(val start: Int, val end: Int, val chunk: ChunkRef)

/** What `answer` returns. */
data class GeneratedAnswer(val text: String, val attributions: List<Attribution>)

/**
 * The generative model, as a **closed set of jobs**.
 *
 * Not a generic `complete(prompt)`. Each job carries its own guarantee — [answer] must
 * never see verbatim-class text, [describeImage] must never produce an answer — and a
 * generic completion method would let a fourth job appear without any of that being
 * considered. The same reasoning closes the capability and constraint vocabularies.
 *
 * **None of these is unconditional.** A typed, self-contained rules question reaches
 * retrieval having invoked nothing here at all. That is the guarantee, and it is also why
 * the app survives a four-hour session: running the model on every query would put every
 * one-word lookup on its latency and thermal path.
 */
interface Generator {
    val availability: Availability

    /** Voice, camera, or a follow-up with an unresolved reference. */
    fun normalize(query: String, history: List<Turn>): String

    /** The part of the question the quote cards did not answer; null if they answered it. */
    fun residualIntent(query: String, covered: List<CardSummary>): String?

    /** Setting answers, over redacted `('setting','source')` chunks only. */
    fun answer(residual: String, context: List<RedactedChunk>): GeneratedAnswer

    /** Camera input. Produces a retrieval query and nothing else. */
    fun describeImage(image: ImageBuffer): String
}
