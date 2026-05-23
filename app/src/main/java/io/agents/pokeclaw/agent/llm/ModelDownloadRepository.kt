// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import java.util.concurrent.TimeUnit

enum class ModelDownloadStatus {
    IDLE,
    QUEUED,
    RUNNING,
    WAITING_NETWORK,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

data class ModelDownloadState(
    val status: ModelDownloadStatus,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val progressPercent: Int = 0,
    val error: String = "",
    val modelPath: String = "",
) {
    val isActive: Boolean
        get() = status == ModelDownloadStatus.QUEUED ||
            status == ModelDownloadStatus.RUNNING ||
            status == ModelDownloadStatus.WAITING_NETWORK
}

object ModelDownloadRepository {
    private const val TAG = "ModelDownloadRepository"
    private const val WORK_PREFIX = "local-model-download-"
    private const val LAST_ERROR_PREFIX = "KEY_MODEL_DOWNLOAD_LAST_ERROR_"

    const val KEY_MODEL_ID = "model_id"
    const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
    const val KEY_TOTAL_BYTES = "total_bytes"
    const val KEY_BYTES_PER_SECOND = "bytes_per_second"
    const val KEY_ERROR = "error"
    const val KEY_MODEL_PATH = "model_path"
    const val KEY_STATUS = "status"

    fun enqueue(context: Context, model: LocalModelManager.ModelInfo) {
        XLog.i(TAG, "enqueue: model=${model.id}, work=${workName(model)}")
        setLastError(model, "")
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(KEY_MODEL_ID to model.id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(workName(model))
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(workName(model), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context, model: LocalModelManager.ModelInfo) {
        XLog.i(TAG, "cancel: model=${model.id}, work=${workName(model)}")
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(model))
    }

    fun observe(
        owner: LifecycleOwner,
        context: Context,
        model: LocalModelManager.ModelInfo,
        onState: (ModelDownloadState) -> Unit,
    ) {
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWorkLiveData(workName(model))
            .observe(owner) { infos ->
                val info = infos?.firstOrNull()
                val available = LocalModelManager.availabilityForModel(
                    context,
                    model,
                    ModelConfigRepository.snapshot().local,
                ).isAvailable
                onState(stateFromWorkInfo(info, available, model))
            }
    }

    internal fun stateFromWorkInfoForTest(
        workInfo: WorkInfo?,
        modelAvailable: Boolean,
    ): ModelDownloadState = stateFromWorkInfo(workInfo, modelAvailable, null)

    private fun stateFromWorkInfo(
        workInfo: WorkInfo?,
        modelAvailable: Boolean,
        model: LocalModelManager.ModelInfo?,
    ): ModelDownloadState {
        if (workInfo == null) {
            return if (modelAvailable) {
                ModelDownloadState(status = ModelDownloadStatus.SUCCEEDED)
            } else {
                ModelDownloadState(status = ModelDownloadStatus.IDLE, error = model?.let { lastError(it) }.orEmpty())
            }
        }

        return when (workInfo.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                val statusText = workInfo.progress.getString(KEY_STATUS).orEmpty()
                ModelDownloadState(
                    status = if (statusText == ModelDownloadStatus.WAITING_NETWORK.name) {
                        ModelDownloadStatus.WAITING_NETWORK
                    } else {
                        ModelDownloadStatus.QUEUED
                    },
                    error = model?.let { lastError(it) }.orEmpty(),
                )
            }
            WorkInfo.State.RUNNING -> {
                val bytes = workInfo.progress.getLong(KEY_BYTES_DOWNLOADED, 0L)
                val total = workInfo.progress.getLong(KEY_TOTAL_BYTES, 0L)
                ModelDownloadState(
                    status = ModelDownloadStatus.RUNNING,
                    bytesDownloaded = bytes,
                    totalBytes = total,
                    bytesPerSecond = workInfo.progress.getLong(KEY_BYTES_PER_SECOND, 0L),
                    progressPercent = percent(bytes, total),
                )
            }
            WorkInfo.State.SUCCEEDED -> {
                ModelDownloadState(
                    status = ModelDownloadStatus.SUCCEEDED,
                    modelPath = workInfo.outputData.getString(KEY_MODEL_PATH).orEmpty(),
                )
            }
            WorkInfo.State.FAILED -> {
                val error = workInfo.outputData.getString(KEY_ERROR)
                    ?: model?.let { lastError(it) }
                    ?: ""
                ModelDownloadState(status = ModelDownloadStatus.FAILED, error = error)
            }
            WorkInfo.State.CANCELLED -> {
                ModelDownloadState(status = ModelDownloadStatus.CANCELLED)
            }
        }
    }

    fun diagnostics(context: Context): String {
        return buildString {
            LocalModelManager.AVAILABLE_MODELS.forEach { model ->
                val modelDir = runCatching { LocalModelManager.getModelDir(context) }.getOrNull()
                val work = runCatching {
                    WorkManager.getInstance(context.applicationContext)
                        .getWorkInfosForUniqueWork(workName(model))
                        .get(2, TimeUnit.SECONDS)
                        .firstOrNull()
                }.getOrNull()
                appendLine("- ${model.id}: work=${work?.state ?: "(none)"}, attempts=${work?.runAttemptCount ?: 0}, lastError=${lastError(model).ifBlank { "(none)" }}")
                if (modelDir != null) {
                    val temp = ModelDownloadMetadataStore.tempFile(modelDir, model)
                    val partialSidecar = ModelDownloadMetadataStore.partialSidecar(modelDir, model)
                    appendLine("  partialBytes=${if (temp.exists()) temp.length() else 0}")
                    appendLine("  partialMetadata=${partialSidecar.takeIf { it.exists() }?.readText()?.take(500) ?: "(none)"}")
                }
            }
        }
    }

    fun setLastError(model: LocalModelManager.ModelInfo, error: String) {
        KVUtils.putString("$LAST_ERROR_PREFIX${model.id}", error)
    }

    private fun lastError(model: LocalModelManager.ModelInfo): String {
        return KVUtils.getString("$LAST_ERROR_PREFIX${model.id}", "")
    }

    fun workName(model: LocalModelManager.ModelInfo): String = "$WORK_PREFIX${model.id}"

    fun progressData(
        bytesDownloaded: Long,
        totalBytes: Long,
        bytesPerSecond: Long,
        status: ModelDownloadStatus = ModelDownloadStatus.RUNNING,
    ): Data {
        return workDataOf(
            KEY_BYTES_DOWNLOADED to bytesDownloaded,
            KEY_TOTAL_BYTES to totalBytes,
            KEY_BYTES_PER_SECOND to bytesPerSecond,
            KEY_STATUS to status.name,
        )
    }

    private fun percent(bytes: Long, total: Long): Int {
        if (bytes <= 0L || total <= 0L) return 0
        return (bytes * 100 / total).toInt().coerceIn(0, 100)
    }
}
