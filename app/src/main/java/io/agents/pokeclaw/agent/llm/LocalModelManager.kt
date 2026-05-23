// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import android.content.Context
import android.os.StatFs
import io.agents.pokeclaw.R
import io.agents.pokeclaw.utils.XLog
import java.io.File
import java.io.FileOutputStream

/**
 * Manages on-device LLM model downloads and storage.
 *
 * Models are downloaded from HuggingFace and stored in the app's
 * external files directory for persistence across app restarts.
 */
object LocalModelManager {

    private const val TAG = "LocalModelManager"

    /** Available models for download */
    data class ModelInfo(
        val id: String,
        val displayName: String,
        val url: String,
        val revision: String,
        val fileName: String,
        val sizeBytes: Long,
        val sha256: String,
        val minRamGb: Int
    )

    data class DeviceSupport(
        val deviceRamGb: Int,
        val minimumBuiltInRamGb: Int,
        val bestSupportedModel: ModelInfo?,
    )

    data class CatalogEntry(
        val model: ModelInfo,
        val isDownloaded: Boolean,
        val isSupported: Boolean,
        val path: String?,
    )

    data class ActiveModelState(
        val displayName: String,
        val metaText: String,
        val statusText: String,
        val statusKind: StatusKind,
    )

    enum class AvailabilitySource {
        MANAGED_DOWNLOAD,
        LINKED_FILE,
        MISSING,
    }

    data class ModelAvailability(
        val isAvailable: Boolean,
        val source: AvailabilitySource,
    )

    enum class StatusKind {
        READY,
        WARNING,
        NEUTRAL,
    }

    val AVAILABLE_MODELS = listOf(
        ModelInfo(
            id = "gemma4-e2b",
            displayName = "Gemma 4 E2B — 2.6GB",
            url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/a4a831c060880f3733135ad22f10e0e9f758f45d/gemma-4-E2B-it.litertlm",
            revision = "a4a831c060880f3733135ad22f10e0e9f758f45d",
            fileName = "gemma-4-E2B-it.litertlm",
            sizeBytes = 2_588_147_712L,
            sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            minRamGb = 8
        ),
        ModelInfo(
            id = "gemma4-e4b",
            displayName = "Gemma 4 E4B — 3.6GB",
            url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/65ce5ba80d8790d66ef11d82d7d079a06f3fef97/gemma-4-E4B-it.litertlm",
            revision = "65ce5ba80d8790d66ef11d82d7d079a06f3fef97",
            fileName = "gemma-4-E4B-it.litertlm",
            sizeBytes = 3_659_530_240L,
            sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
            minRamGb = 10
        ),
    )

    /**
     * Pick the best model for this device based on available RAM.
     * Devices with 12GB+ RAM get E4B, everyone else gets E2B.
     */
    fun recommendedModel(context: Context): ModelInfo {
        val totalRamGb = getDeviceRamGb(context)
        return if (totalRamGb >= 12) {
            AVAILABLE_MODELS.first { it.id == "gemma4-e4b" }
        } else {
            AVAILABLE_MODELS.first { it.id == "gemma4-e2b" }
        }
    }

    fun getDeviceRamGb(context: Context): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return (memInfo.totalMem / (1024L * 1024L * 1024L)).toInt() + 1
    }

    fun deviceSupport(context: Context): DeviceSupport {
        val deviceRamGb = getDeviceRamGb(context)
        return DeviceSupport(
            deviceRamGb = deviceRamGb,
            minimumBuiltInRamGb = AVAILABLE_MODELS.minOf { it.minRamGb },
            bestSupportedModel = AVAILABLE_MODELS
                .filter { it.minRamGb <= deviceRamGb }
                .maxByOrNull { it.minRamGb }
        )
    }

    fun bestSupportedModel(context: Context): ModelInfo? {
        return deviceSupport(context).bestSupportedModel
    }

    fun isModelSupportedOnDevice(context: Context, model: ModelInfo): Boolean {
        return deviceSupport(context).deviceRamGb >= model.minRamGb
    }

    fun catalog(context: Context): List<CatalogEntry> {
        val support = deviceSupport(context)
        return AVAILABLE_MODELS.map { model ->
            CatalogEntry(
                model = model,
                isDownloaded = isModelDownloaded(context, model),
                isSupported = model.minRamGb <= support.deviceRamGb,
                path = getModelPath(context, model),
            )
        }
    }

    fun configuredBuiltInModel(localConfig: LocalModelConfig): ModelInfo? {
        return AVAILABLE_MODELS.find { matchesConfiguredModel(it, localConfig) }
    }

    fun availabilityForModel(
        context: Context,
        model: ModelInfo,
        localConfig: LocalModelConfig? = null
    ): ModelAvailability {
        if (isModelDownloaded(context, model)) {
            return ModelAvailability(
                isAvailable = true,
                source = AvailabilitySource.MANAGED_DOWNLOAD,
            )
        }

        val config = localConfig ?: return ModelAvailability(
            isAvailable = false,
            source = AvailabilitySource.MISSING,
        )

        val linkedFileExists = config.modelPath.isNotBlank() && File(config.modelPath).exists()
        if (linkedFileExists && matchesConfiguredModel(model, config)) {
            return ModelAvailability(
                isAvailable = true,
                source = AvailabilitySource.LINKED_FILE,
            )
        }

        return ModelAvailability(
            isAvailable = false,
            source = AvailabilitySource.MISSING,
        )
    }

    fun resolveActiveModelState(context: Context, localConfig: LocalModelConfig): ActiveModelState {
        val modelPath = localConfig.modelPath
        if (modelPath.isBlank()) {
            return ActiveModelState(
                displayName = context.getString(R.string.models_no_model_selected_error),
                metaText = context.getString(R.string.models_download_model_below),
                statusText = context.getString(R.string.models_not_configured_dot),
                statusKind = StatusKind.NEUTRAL,
            )
        }

        val matchedModel = configuredBuiltInModel(localConfig)
        if (matchedModel != null) {
            val availability = availabilityForModel(context, matchedModel, localConfig)
            return ActiveModelState(
                displayName = matchedModel.displayName,
                metaText = "${matchedModel.fileName} · ${context.getString(R.string.models_on_device)}",
                statusText = when (availability.source) {
                    AvailabilitySource.MANAGED_DOWNLOAD -> context.getString(R.string.models_ready_dot)
                    AvailabilitySource.LINKED_FILE -> context.getString(R.string.models_ready_dot)
                    AvailabilitySource.MISSING -> context.getString(R.string.models_missing_file_dot)
                },
                statusKind = if (availability.isAvailable) StatusKind.READY else StatusKind.WARNING,
            )
        }

        return ActiveModelState(
            displayName = localConfig.displayName.ifBlank { File(modelPath).nameWithoutExtension },
            metaText = context.getString(R.string.models_on_device),
            statusText = if (File(modelPath).exists()) {
                context.getString(R.string.models_ready_dot)
            } else {
                context.getString(R.string.models_missing_file_dot)
            },
            statusKind = if (File(modelPath).exists()) StatusKind.READY else StatusKind.WARNING,
        )
    }

    interface DownloadCallback {
        fun onProgress(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long)
        fun onComplete(modelPath: String)
        fun onError(error: String)
    }

    data class ModelStorageDiagnostics(
        val selectedDir: String?,
        val selectedAvailableBytes: Long?,
        val selectedError: String?,
        val externalDir: String,
        val externalStatus: String,
        val internalDir: String,
        val internalStatus: String,
    )

    /**
     * Get the directory where models are stored.
     */
    fun getModelDir(context: Context): File {
        return resolveUsableModelDir(
            externalRoot = context.getExternalFilesDir(null),
            internalRoot = context.filesDir,
        )
    }

    internal fun resolveUsableModelDir(
        externalRoot: File?,
        internalRoot: File,
        canWriteDirectory: (File) -> Boolean = ::canWriteToDirectory,
    ): File {
        val externalDir = externalRoot?.let { File(it, "models") }
        if (externalDir != null && prepareModelDirectory(externalDir, canWriteDirectory)) {
            return externalDir
        }

        val internalDir = File(internalRoot, "models")
        if (prepareModelDirectory(internalDir, canWriteDirectory)) {
            return internalDir
        }

        throw IllegalStateException(
            "Could not create model storage directory at ${externalDir?.absolutePath ?: "(no external dir)"} or ${internalDir.absolutePath}"
        )
    }

    fun storageDiagnostics(context: Context): ModelStorageDiagnostics {
        val externalDir = context.getExternalFilesDir(null)?.let { File(it, "models") }
        val internalDir = File(context.filesDir, "models")
        val selected = runCatching { getModelDir(context) }

        return ModelStorageDiagnostics(
            selectedDir = selected.getOrNull()?.absolutePath,
            selectedAvailableBytes = selected.getOrNull()?.let { availableBytes(it) },
            selectedError = selected.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" },
            externalDir = externalDir?.absolutePath ?: "(no external files dir)",
            externalStatus = describeModelDirectory(externalDir),
            internalDir = internalDir.absolutePath,
            internalStatus = describeModelDirectory(internalDir),
        )
    }

    private fun prepareModelDirectory(dir: File, canWriteDirectory: (File) -> Boolean): Boolean {
        if (!ensureDirectory(dir)) {
            logWarning("Model directory is not usable: could not create ${dir.absolutePath}")
            return false
        }
        if (!canWriteDirectory(dir)) {
            logWarning("Model directory is not usable: write probe failed for ${dir.absolutePath}")
            return false
        }
        return true
    }

    private fun ensureDirectory(dir: File): Boolean {
        return dir.isDirectory || dir.mkdirs() || dir.isDirectory
    }

    private fun canWriteToDirectory(dir: File): Boolean {
        val probe = File(dir, ".pokeclaw-write-probe")
        return runCatching {
            FileOutputStream(probe, false).use { output ->
                output.write(1)
            }
            if (probe.exists() && !probe.delete()) {
                logWarning("Could not delete model storage probe: ${probe.absolutePath}")
            }
            true
        }.getOrElse { e ->
            logWarning("Model storage write probe failed: ${dir.absolutePath}", e)
            false
        }
    }

    private fun logWarning(message: String, throwable: Throwable? = null) {
        runCatching {
            if (throwable == null) {
                XLog.w(TAG, message)
            } else {
                XLog.w(TAG, message, throwable)
            }
        }
    }

    private fun describeModelDirectory(dir: File?): String {
        if (dir == null) return "unavailable"
        val stat = runCatching { StatFs(dir.absolutePath).availableBytes }
        return listOf(
            "exists=${dir.exists()}",
            "isDirectory=${dir.isDirectory}",
            "canRead=${dir.canRead()}",
            "canWrite=${dir.canWrite()}",
            "availableBytes=${stat.getOrNull() ?: "(unknown)"}",
            "statError=${stat.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "(none)"}",
        ).joinToString(", ")
    }

    private fun availableBytes(dir: File): Long? {
        return runCatching { StatFs(dir.absolutePath).availableBytes }.getOrNull()
    }

    /**
     * Check if a model is already downloaded.
     */
    fun isModelDownloaded(context: Context, model: ModelInfo): Boolean {
        val file = File(getModelDir(context), model.fileName)
        return isValidManagedModelFile(file, model)
    }

    /**
     * Get the path to a downloaded model.
     */
    fun getModelPath(context: Context, model: ModelInfo): String? {
        val file = File(getModelDir(context), model.fileName)
        return if (isValidManagedModelFile(file, model)) file.absolutePath else null
    }

    private fun matchesConfiguredModel(model: ModelInfo, localConfig: LocalModelConfig): Boolean {
        if (localConfig.modelId.equals(model.id, ignoreCase = true)) return true

        val modelPath = localConfig.modelPath.lowercase()
        if (modelPath.endsWith(model.fileName.lowercase())) return true

        val display = localConfig.displayName.lowercase()
        return builtInAliases(model).any { alias ->
            modelPath.contains(alias) || display.contains(alias)
        }
    }

    private fun builtInAliases(model: ModelInfo): List<String> {
        return when (model.id) {
            "gemma4-e2b" -> listOf(
                "gemma4-e2b",
                "gemma-4-e2b",
                "gemma 4 e2b",
                "gemma4_2b",
                "gemma-4-2b",
                "gemma 4 2b",
            )
            "gemma4-e4b" -> listOf(
                "gemma4-e4b",
                "gemma-4-e4b",
                "gemma 4 e4b",
                "gemma4_4b",
                "gemma-4-4b",
                "gemma 4 4b",
            )
            else -> emptyList()
        }
    }

    /**
     * Download a model from HuggingFace with progress reporting.
     * Supports resume via HTTP Range headers for partial downloads.
     *
     * Must be called from a background thread.
     */
    fun downloadModel(
        context: Context,
        model: ModelInfo,
        callback: DownloadCallback
    ) {
        try {
            val path = ModelDownloadEngine().download(
                modelDir = getModelDir(context),
                model = model,
                listener = object : ModelDownloadListener {
                    override fun onProgress(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long) {
                        callback.onProgress(bytesDownloaded, totalBytes, bytesPerSecond)
                    }
                },
            )
            callback.onComplete(path)
        } catch (e: ModelDownloadCancelledException) {
            XLog.w(TAG, "Download cancelled")
            callback.onError("Download cancelled")
        } catch (e: ModelDownloadException) {
            XLog.e(TAG, "Download failed: ${e.message}", e)
            callback.onError(e.message ?: "Download failed")
        } catch (e: Exception) {
            XLog.e(TAG, "Download failed", e)
            callback.onError("Download failed: ${e.message}")
        }
    }

    /**
     * Delete a downloaded model to free space.
     */
    fun deleteModel(context: Context, model: ModelInfo): Boolean {
        val modelDir = getModelDir(context)
        val file = ModelDownloadMetadataStore.targetFile(modelDir, model)
        val tempFile = ModelDownloadMetadataStore.tempFile(modelDir, model)
        val partialSidecar = ModelDownloadMetadataStore.partialSidecar(modelDir, model)
        val finalSidecar = ModelDownloadMetadataStore.finalSidecar(modelDir, model)
        tempFile.delete()
        partialSidecar.delete()
        finalSidecar.delete()
        return if (file.exists()) file.delete() else true
    }

    internal fun isValidManagedModelFile(file: File, model: ModelInfo): Boolean {
        if (!file.exists()) return false
        val length = file.length()
        if (length != model.sizeBytes) return false
        val metadata = ModelDownloadMetadataStore.read(
            ModelDownloadMetadataStore.finalSidecar(file.parentFile ?: return false, model)
        )
        return ModelDownloadMetadataStore.matchesModel(metadata, model) &&
            metadata?.checksumVerified == true
    }
}
