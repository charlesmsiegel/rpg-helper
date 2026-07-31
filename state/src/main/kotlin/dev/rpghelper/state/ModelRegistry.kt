package dev.rpghelper.state

/** What a model is for. Closed, because each role has a different degradation story. */
enum class ModelRole { EMBEDDER, ASR, GENERATIVE;

    /** The `role` column's spelling, which the DDL's CHECK constraint names. */
    val stored: String get() = name.lowercase()
}

/**
 * Where a model has got to.
 *
 * [FAILED] is distinct from [ABSENT] on purpose, and the distinction survives into the
 * card the user sees: someone who already chose to download must not be asked to choose
 * again, and someone who never chose must not be told something went wrong.
 */
enum class ModelState { ABSENT, DOWNLOADING, READY, FAILED;

    val stored: String get() = name.lowercase()

    companion object {
        fun of(stored: String): ModelState? = entries.firstOrNull { it.stored == stored }
    }
}

/** One row of the `models` table. */
data class ModelStatus(
    val modelId: String,
    val role: ModelRole,
    val state: ModelState,
    val bytesTotal: Long?,
    val bytesFetched: Long?,
    val updatedAt: String,
    /** Set when [state] is [ModelState.FAILED]; the reason shown on the card. */
    val reason: String? = null,
)

/**
 * Download state for the three on-device models, across restarts.
 *
 * The downloader itself knows how to resume from bytes on disk; what it cannot know is
 * what the *user* has agreed to. A model half-fetched when the app was killed is a
 * download the user started and expects to see continue, and one that failed is a state
 * they need told about rather than silently retried into the same wall. That is what this
 * table is for, and it is why progress is recorded here as well as being reported live.
 *
 * **A progress row is not proof of bytes.** The files are the truth; this is a record of
 * intent and of the last thing observed. So `ready` is written only after the downloader
 * has verified digests, and a caller that finds `ready` for a model whose files are gone
 * gets a corrected row rather than a crash — the same direction of caution the pack
 * library takes with a file it can no longer read.
 */
class ModelRegistry(
    private val db: StateDb,
    private val clock: () -> String = { java.time.Instant.now().toString() },
) {

    fun statusOf(modelId: String): ModelStatus? = db.query(
        "SELECT model_id, role, state, bytes_total, bytes_fetched, updated_at " +
            "FROM models WHERE model_id = ?",
        modelId,
    ) { it.toStatus() }.singleOrNull()

    fun all(): List<ModelStatus> = db.query(
        "SELECT model_id, role, state, bytes_total, bytes_fetched, updated_at " +
            "FROM models ORDER BY model_id",
    ) { it.toStatus() }

    /** Records that the user asked for this model, before a byte has arrived. */
    fun beginDownload(modelId: String, role: ModelRole, bytesTotal: Long) {
        require(bytesTotal > 0) { "a download of $bytesTotal bytes is not a download" }
        write(modelId, role, ModelState.DOWNLOADING, bytesTotal, 0L)
    }

    /**
     * Updates progress, without touching what the row already knows.
     *
     * Ignored for a model that has no row: progress for a download nobody started is
     * either a stale callback from a cancelled fetch or a bug, and inventing a row from it
     * would make the table say the user agreed to something they did not.
     */
    fun progress(modelId: String, bytesFetched: Long) {
        db.execute(
            "UPDATE models SET bytes_fetched = ?, updated_at = ? " +
                "WHERE model_id = ? AND state = ?",
            bytesFetched, clock(), modelId, ModelState.DOWNLOADING.stored,
        )
    }

    /** Written **after** the downloader has verified every digest, never before. */
    fun ready(modelId: String, role: ModelRole, bytesTotal: Long) {
        write(modelId, role, ModelState.READY, bytesTotal, bytesTotal)
    }

    /**
     * Records a failure and why.
     *
     * The reason is kept because the card shows it. "Could not be loaded" with no cause is
     * a message that tells a user only that they cannot fix it.
     */
    fun failed(modelId: String, role: ModelRole, reason: String) {
        db.transaction {
            val existing = statusOf(modelId)
            write(
                modelId, role, ModelState.FAILED,
                existing?.bytesTotal, existing?.bytesFetched, reason,
            )
        }
    }

    /** Forgets a model entirely — the row and the user's agreement along with it. */
    fun forget(modelId: String) {
        db.execute("DELETE FROM models WHERE model_id = ?", modelId)
    }

    private fun write(
        modelId: String,
        role: ModelRole,
        state: ModelState,
        bytesTotal: Long?,
        bytesFetched: Long?,
        reason: String? = null,
    ) {
        db.execute(
            "INSERT INTO models (model_id, role, state, bytes_total, bytes_fetched, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(model_id) DO UPDATE SET role = excluded.role, " +
                "state = excluded.state, bytes_total = excluded.bytes_total, " +
                "bytes_fetched = excluded.bytes_fetched, updated_at = excluded.updated_at",
            modelId, role.stored, stateColumn(state, reason), bytesTotal, bytesFetched, clock(),
        )
    }

    /**
     * `failed` carries its reason in the same column.
     *
     * The DDL gives `state` no companion column, and adding one is a migration every
     * device has to run to store a string only one of four states ever has. The prefix is
     * unambiguous because the four state names are fixed and none contains a colon.
     */
    private fun stateColumn(state: ModelState, reason: String?): String =
        if (state == ModelState.FAILED && reason != null) "${state.stored}:$reason" else state.stored

    private fun StateDb.Row.toStatus(): ModelStatus {
        val raw = string(2)
        val state = ModelState.of(raw.substringBefore(':'))
            ?: error("models.state holds '$raw', which is not a state this build knows")
        return ModelStatus(
            modelId = string(0),
            role = ModelRole.valueOf(string(1).uppercase()),
            state = state,
            bytesTotal = longOrNull(3),
            bytesFetched = longOrNull(4),
            updatedAt = string(5),
            reason = if (state == ModelState.FAILED && ':' in raw) raw.substringAfter(':') else null,
        )
    }
}
