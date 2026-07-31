package dev.rpghelper.pack

import java.nio.file.Path

/** Entry point for the activation gate. */
object Packs {

    /**
     * Opens [path] read-only and reports whether it may be activated.
     *
     * @param supportedEmbedders contracts whose weights the calling build bundles.
     */
    fun validateFile(path: Path, supportedEmbedders: Set<EmbedderContract>): ValidationReport =
        JdbcDb.openReadOnly(path).use { PackValidator(supportedEmbedders).validate(it) }
}
