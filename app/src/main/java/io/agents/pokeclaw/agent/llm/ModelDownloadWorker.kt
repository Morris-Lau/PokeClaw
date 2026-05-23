// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.agents.pokeclaw.R
import io.agents.pokeclaw.utils.XLog

class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    companion object {
        private const val TAG = "ModelDownloadWorker"
        const val CHANNEL_ID = "PokeClaw_model_download_channel"
        private const val NOTIFICATION_BASE_ID = 2200
    }

    override fun doWork(): Result {
        val modelId = inputData.getString(ModelDownloadRepository.KEY_MODEL_ID).orEmpty()
        val model = LocalModelManager.AVAILABLE_MODELS.find { it.id == modelId }
            ?: return Result.failure(workDataOf(ModelDownloadRepository.KEY_ERROR to "Unknown model: $modelId"))
        createNotificationChannel()
        setForegroundAsync(foregroundInfo(model, 0, 0L)).get()
        setProgressAsync(ModelDownloadRepository.progressData(0L, model.sizeBytes, 0L)).get()
        XLog.i(TAG, "doWork: starting model=${model.id}, attempt=$runAttemptCount")

        return try {
            val path = ModelDownloadEngine().download(
                modelDir = LocalModelManager.getModelDir(applicationContext),
                model = model,
                listener = object : ModelDownloadListener {
                    override fun onProgress(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long) {
                        if (isStopped) throw ModelDownloadCancelledException()
                        val data = ModelDownloadRepository.progressData(
                            bytesDownloaded = bytesDownloaded,
                            totalBytes = totalBytes,
                            bytesPerSecond = bytesPerSecond,
                        )
                        setProgressAsync(data)
                        setForegroundAsync(foregroundInfo(model, data.getIntProgress(), bytesPerSecond))
                    }
                },
                shouldStop = { isStopped },
            )
            XLog.i(TAG, "doWork: completed model=${model.id}, path=$path")
            ModelDownloadRepository.setLastError(model, "")
            Result.success(workDataOf(ModelDownloadRepository.KEY_MODEL_PATH to path))
        } catch (e: ModelDownloadCancelledException) {
            XLog.w(TAG, "doWork: cancelled model=${model.id}")
            Result.failure(workDataOf(ModelDownloadRepository.KEY_ERROR to applicationContext.getString(R.string.models_download_cancelled)))
        } catch (e: ModelDownloadException) {
            val message = e.message ?: applicationContext.getString(R.string.models_download_failed)
            XLog.e(TAG, "doWork: failed model=${model.id}, retryable=${e.retryable}: $message", e)
            ModelDownloadRepository.setLastError(model, message)
            if (e.retryable) {
                setProgressAsync(
                    ModelDownloadRepository.progressData(
                        bytesDownloaded = 0L,
                        totalBytes = model.sizeBytes,
                        bytesPerSecond = 0L,
                        status = ModelDownloadStatus.WAITING_NETWORK,
                    )
                ).get()
                Result.retry()
            } else {
                Result.failure(workDataOf(ModelDownloadRepository.KEY_ERROR to message))
            }
        } catch (e: Exception) {
            val message = e.message ?: applicationContext.getString(R.string.models_download_failed)
            XLog.e(TAG, "doWork: unexpected failure model=${model.id}: $message", e)
            ModelDownloadRepository.setLastError(model, message)
            Result.retry()
        }
    }

    private fun foregroundInfo(
        model: LocalModelManager.ModelInfo,
        progressPercent: Int,
        bytesPerSecond: Long,
    ): ForegroundInfo {
        val notification = buildNotification(model, progressPercent, bytesPerSecond)
        val notificationId = NOTIFICATION_BASE_ID + model.id.hashCode().and(0x3ff)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun buildNotification(
        model: LocalModelManager.ModelInfo,
        progressPercent: Int,
        bytesPerSecond: Long,
    ): Notification {
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val speedText = if (bytesPerSecond > 0L) {
            " · ${bytesPerSecond / 1_000_000} MB/s"
        } else {
            ""
        }
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(applicationContext.getString(R.string.models_download_notification_title))
            .setContentText("${model.displayName}: $progressPercent%$speedText")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progressPercent.coerceIn(0, 100), false)
            .addAction(
                R.drawable.ic_close,
                applicationContext.getString(R.string.common_cancel),
                cancelIntent,
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.models_download_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = applicationContext.getString(R.string.models_download_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun androidx.work.Data.getIntProgress(): Int {
        val bytes = getLong(ModelDownloadRepository.KEY_BYTES_DOWNLOADED, 0L)
        val total = getLong(ModelDownloadRepository.KEY_TOTAL_BYTES, 0L)
        if (bytes <= 0L || total <= 0L) return 0
        return (bytes * 100 / total).toInt().coerceIn(0, 100)
    }
}
