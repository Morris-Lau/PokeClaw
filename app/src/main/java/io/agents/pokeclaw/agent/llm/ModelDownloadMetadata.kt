// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import com.google.gson.Gson
import java.io.File

internal data class ModelDownloadMetadata(
    val modelId: String = "",
    val url: String = "",
    val revision: String = "",
    val expectedSizeBytes: Long = 0L,
    val sha256: String = "",
    val partialBytes: Long = 0L,
    val updatedAtMs: Long = 0L,
    val checksumVerified: Boolean = false,
    val completedAtMs: Long = 0L,
    val lastError: String = "",
)

internal object ModelDownloadMetadataStore {
    private val gson = Gson()

    fun targetFile(modelDir: File, model: LocalModelManager.ModelInfo): File =
        File(modelDir, model.fileName)

    fun tempFile(modelDir: File, model: LocalModelManager.ModelInfo): File =
        File(modelDir, "${model.fileName}.downloading")

    fun partialSidecar(modelDir: File, model: LocalModelManager.ModelInfo): File =
        File(modelDir, "${model.fileName}.downloading.json")

    fun finalSidecar(modelDir: File, model: LocalModelManager.ModelInfo): File =
        File(modelDir, "${model.fileName}.metadata.json")

    fun read(file: File): ModelDownloadMetadata? {
        if (!file.exists()) return null
        return runCatching {
            gson.fromJson(file.readText(), ModelDownloadMetadata::class.java)
        }.getOrNull()
    }

    fun write(file: File, metadata: ModelDownloadMetadata) {
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(metadata))
    }

    fun metadataFor(
        model: LocalModelManager.ModelInfo,
        partialBytes: Long = 0L,
        nowMs: Long,
        checksumVerified: Boolean = false,
        completedAtMs: Long = 0L,
        lastError: String = "",
    ): ModelDownloadMetadata {
        return ModelDownloadMetadata(
            modelId = model.id,
            url = model.url,
            revision = model.revision,
            expectedSizeBytes = model.sizeBytes,
            sha256 = model.sha256,
            partialBytes = partialBytes,
            updatedAtMs = nowMs,
            checksumVerified = checksumVerified,
            completedAtMs = completedAtMs,
            lastError = lastError,
        )
    }

    fun matchesModel(metadata: ModelDownloadMetadata?, model: LocalModelManager.ModelInfo): Boolean {
        return metadata != null &&
            metadata.modelId == model.id &&
            metadata.url == model.url &&
            metadata.revision == model.revision &&
            metadata.expectedSizeBytes == model.sizeBytes &&
            metadata.sha256.equals(model.sha256, ignoreCase = true)
    }
}
