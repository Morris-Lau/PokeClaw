// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import android.os.StatFs
import io.agents.pokeclaw.utils.XLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

interface ModelDownloadListener {
    fun onProgress(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long)
}

class ModelDownloadException(
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
) : Exception(message, cause)

class ModelDownloadCancelledException : Exception("Download cancelled")

internal class ModelDownloadEngine(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        private const val TAG = "ModelDownloadEngine"
        private const val BUFFER_SIZE = 128 * 1024
    }

    fun download(
        modelDir: File,
        model: LocalModelManager.ModelInfo,
        listener: ModelDownloadListener,
        shouldStop: () -> Boolean = { false },
    ): String {
        if (!modelDir.isDirectory && !modelDir.mkdirs() && !modelDir.isDirectory) {
            throw ModelDownloadException("Could not create model storage directory", retryable = false)
        }

        val targetFile = ModelDownloadMetadataStore.targetFile(modelDir, model)
        val tempFile = ModelDownloadMetadataStore.tempFile(modelDir, model)
        val partialSidecar = ModelDownloadMetadataStore.partialSidecar(modelDir, model)

        if (LocalModelManager.isValidManagedModelFile(targetFile, model)) {
            logI("download: managed model already valid at ${targetFile.absolutePath}")
            return targetFile.absolutePath
        }

        cleanupInvalidCompletedFile(targetFile, model)
        val remoteSize = verifyRemoteMetadata(model)
        preparePartial(model, tempFile, partialSidecar)

        var existingBytes = tempFile.takeIf { it.exists() }?.length() ?: 0L
        checkStorage(modelDir, model, existingBytes)

        val requestBuilder = Request.Builder().url(model.url)
        if (existingBytes > 0L) {
            requestBuilder.addHeader("Range", "bytes=$existingBytes-")
            logI("download: resuming ${model.id} from byte $existingBytes")
        } else {
            logI("download: starting ${model.id} from byte 0")
        }

        val response = try {
            client.newCall(requestBuilder.build()).execute()
        } catch (e: IOException) {
            throw ModelDownloadException("Network error: ${e.message}", retryable = true, cause = e)
        }

        response.use { resp ->
            if (existingBytes > 0L && resp.code == 416) {
                logW("download: server returned 416 for ${model.id}; validating existing partial")
                if (verifyFile(tempFile, model)) {
                    return completeDownload(modelDir, model, tempFile)
                }
                tempFile.delete()
                partialSidecar.delete()
                throw ModelDownloadException("Partial download was not usable; retry download", retryable = true)
            }

            if (!resp.isSuccessful && resp.code != 206) {
                throw httpFailure(resp.code)
            }

            val isResumedResponse = existingBytes > 0L && resp.code == 206
            if (existingBytes > 0L && !isResumedResponse) {
                logW("download: server ignored Range for ${model.id}; restarting from scratch")
                tempFile.delete()
                partialSidecar.delete()
                existingBytes = 0L
                ModelDownloadMetadataStore.write(
                    partialSidecar,
                    ModelDownloadMetadataStore.metadataFor(model, nowMs = nowMs()),
                )
            }

            val body = resp.body ?: throw ModelDownloadException("Empty response body", retryable = true)
            val totalBytes = remoteSize ?: model.sizeBytes
            val append = isResumedResponse
            var downloadedBytes = if (append) existingBytes else 0L
            var lastReportTime = nowMs()
            var lastReportedBytes = downloadedBytes

            body.byteStream().use { input ->
                FileOutputStream(tempFile, append).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (shouldStop()) throw ModelDownloadCancelledException()
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val now = nowMs()
                        if (now - lastReportTime >= 200L) {
                            val elapsed = ((now - lastReportTime).coerceAtLeast(1L)) / 1000.0
                            val speed = ((downloadedBytes - lastReportedBytes) / elapsed).toLong()
                            ModelDownloadMetadataStore.write(
                                partialSidecar,
                                ModelDownloadMetadataStore.metadataFor(
                                    model = model,
                                    partialBytes = downloadedBytes,
                                    nowMs = now,
                                )
                            )
                            listener.onProgress(downloadedBytes, totalBytes, speed)
                            lastReportTime = now
                            lastReportedBytes = downloadedBytes
                        }
                    }
                }
            }
        }

        if (tempFile.length() != model.sizeBytes) {
            logW("download: incomplete file for ${model.id}: ${tempFile.length()} expected ${model.sizeBytes}")
            throw ModelDownloadException("Downloaded file is incomplete. PokeClaw will retry.", retryable = true)
        }

        if (!verifyFile(tempFile, model)) {
            tempFile.delete()
            partialSidecar.delete()
            throw ModelDownloadException("Downloaded file checksum did not match. Please retry.", retryable = false)
        }

        return completeDownload(modelDir, model, tempFile)
    }

    private fun verifyRemoteMetadata(model: LocalModelManager.ModelInfo): Long? {
        logD("verifyRemoteMetadata: HEAD ${model.url}")
        val response = try {
            client.newCall(Request.Builder().url(model.url).head().build()).execute()
        } catch (e: IOException) {
            throw ModelDownloadException("Network error checking model metadata: ${e.message}", retryable = true, cause = e)
        }
        response.use { resp ->
            if (!resp.isSuccessful) throw httpFailure(resp.code)
            val remoteSize = resp.header("X-Linked-Size")?.toLongOrNull()
                ?: resp.header("Content-Length")?.toLongOrNull()
            val remoteEtag = resp.header("X-Linked-ETag")?.trim('"')
                ?: resp.header("ETag")?.trim('"')
            logI(
                "verifyRemoteMetadata: model=${model.id}, size=$remoteSize, etag=${remoteEtag ?: "(none)"}"
            )
            if (remoteSize != null && remoteSize != model.sizeBytes) {
                throw ModelDownloadException(
                    "Remote model size changed: expected ${model.sizeBytes}, got $remoteSize",
                    retryable = false,
                )
            }
            return remoteSize
        }
    }

    private fun preparePartial(
        model: LocalModelManager.ModelInfo,
        tempFile: File,
        partialSidecar: File,
    ) {
        val metadata = ModelDownloadMetadataStore.read(partialSidecar)
        val partialMatches = ModelDownloadMetadataStore.matchesModel(metadata, model)
        val partialLength = tempFile.takeIf { it.exists() }?.length() ?: 0L
        if (!partialMatches || partialLength < 0L || partialLength > model.sizeBytes) {
            if (tempFile.exists() || partialSidecar.exists()) {
                logW(
                    "preparePartial: discarding partial for ${model.id}; matches=$partialMatches length=$partialLength"
                )
            }
            tempFile.delete()
            partialSidecar.delete()
        }
        if (!partialSidecar.exists()) {
            ModelDownloadMetadataStore.write(
                partialSidecar,
                ModelDownloadMetadataStore.metadataFor(model, partialBytes = tempFile.length(), nowMs = nowMs())
            )
        }
    }

    private fun checkStorage(modelDir: File, model: LocalModelManager.ModelInfo, existingBytes: Long) {
        val availableBytes = runCatching { StatFs(modelDir.absolutePath).availableBytes }.getOrNull() ?: return
        val bytesNeeded = (model.sizeBytes - existingBytes).coerceAtLeast(0L)
        if (bytesNeeded > 0L && availableBytes < bytesNeeded) {
            val needGb = String.format("%.1f", bytesNeeded / 1_000_000_000.0)
            val haveGb = String.format("%.1f", availableBytes / 1_000_000_000.0)
            logE("checkStorage: not enough storage for ${model.id}: need ${needGb}GB, have ${haveGb}GB")
            throw ModelDownloadException(
                "Not enough storage: need ${needGb} GB free, only ${haveGb} GB available",
                retryable = false,
            )
        }
        logD("checkStorage: need=${bytesNeeded / 1_000_000}MB have=${availableBytes / 1_000_000}MB")
    }

    private fun completeDownload(
        modelDir: File,
        model: LocalModelManager.ModelInfo,
        tempFile: File,
    ): String {
        val targetFile = ModelDownloadMetadataStore.targetFile(modelDir, model)
        val partialSidecar = ModelDownloadMetadataStore.partialSidecar(modelDir, model)
        val finalSidecar = ModelDownloadMetadataStore.finalSidecar(modelDir, model)
        if (targetFile.exists() && !targetFile.delete()) {
            throw ModelDownloadException("Could not replace existing model file", retryable = false)
        }
        if (!tempFile.renameTo(targetFile)) {
            throw ModelDownloadException("Download finished but PokeClaw could not move the model into place", retryable = false)
        }
        ModelDownloadMetadataStore.write(
            finalSidecar,
            ModelDownloadMetadataStore.metadataFor(
                model = model,
                partialBytes = model.sizeBytes,
                nowMs = nowMs(),
                checksumVerified = true,
                completedAtMs = nowMs(),
            )
        )
        partialSidecar.delete()
        logI("completeDownload: model=${model.id}, path=${targetFile.absolutePath}, bytes=${targetFile.length()}")
        return targetFile.absolutePath
    }

    private fun cleanupInvalidCompletedFile(targetFile: File, model: LocalModelManager.ModelInfo) {
        if (targetFile.exists() && !LocalModelManager.isValidManagedModelFile(targetFile, model)) {
            logW("cleanupInvalidCompletedFile: removing invalid managed file ${targetFile.absolutePath}")
            targetFile.delete()
            ModelDownloadMetadataStore.finalSidecar(targetFile.parentFile ?: return, model).delete()
        }
    }

    private fun verifyFile(file: File, model: LocalModelManager.ModelInfo): Boolean {
        if (!file.exists() || file.length() != model.sizeBytes) return false
        val actual = sha256(file)
        val ok = actual.equals(model.sha256, ignoreCase = true)
        logI("verifyFile: model=${model.id}, checksumOk=$ok")
        return ok
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun httpFailure(code: Int): ModelDownloadException {
        val retryable = code == 429 || code in 500..599
        logW("httpFailure: code=$code retryable=$retryable")
        return ModelDownloadException("Download failed: HTTP $code", retryable = retryable)
    }

    private fun logD(message: String) {
        runCatching { XLog.d(TAG, message) }
    }

    private fun logI(message: String) {
        runCatching { XLog.i(TAG, message) }
    }

    private fun logW(message: String) {
        runCatching { XLog.w(TAG, message) }
    }

    private fun logE(message: String) {
        runCatching { XLog.e(TAG, message) }
    }
}
