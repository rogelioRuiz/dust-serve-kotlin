/*
 * Copyright 2026 T6X
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.t6x.dust.serve

import io.t6x.dust.core.DustCoreError
import io.t6x.dust.core.ModelDescriptor
import io.t6x.dust.core.ModelStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class DownloadManager(
    private val dataSource: DownloadDataSource,
    private val stateStore: ModelStateStore,
    private val networkPolicyProvider: NetworkPolicyProvider,
    private val diskSpaceProvider: DiskSpaceProvider,
    private val baseDir: File,
    private val eventEmitter: (String, Map<String, Any?>) -> Unit,
) {
    companion object {
        private const val PROGRESS_EVENT_INTERVAL_BYTES = 512L * 1024L
    }

    private val activeDownloads = ConcurrentHashMap<String, ActiveDownload>()

    fun download(descriptor: ModelDescriptor, scope: CoroutineScope): Job {
        val url = descriptor.url
        if (url.isNullOrBlank()) {
            return failImmediately(
                descriptor = descriptor,
                scope = scope,
                error = DustCoreError.DownloadFailed("Model descriptor is missing a valid download URL"),
            )
        }

        val expectedHash = descriptor.sha256?.lowercase(Locale.US)
        if (expectedHash.isNullOrBlank()) {
            return failImmediately(
                descriptor = descriptor,
                scope = scope,
                error = DustCoreError.VerificationFailed("Model descriptor is missing a SHA-256 checksum"),
            )
        }

        while (true) {
            val existing = activeDownloads[descriptor.id]
            if (existing != null) {
                if (!existing.job.isCompleted) {
                    return existing.job
                }
                activeDownloads.remove(descriptor.id, existing)
                continue
            }

            lateinit var activeDownload: ActiveDownload
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    runDownload(descriptor = descriptor, url = url, expectedHash = expectedHash)
                } catch (error: Exception) {
                    handleFailure(descriptor = descriptor, error = error)
                } finally {
                    activeDownloads.remove(descriptor.id, activeDownload)
                }
            }
            activeDownload = ActiveDownload(url = url, job = job)

            val raced = activeDownloads.putIfAbsent(descriptor.id, activeDownload)
            if (raced == null) {
                job.start()
                return job
            }

            job.cancel()
            if (!raced.job.isCompleted) {
                return raced.job
            }
            activeDownloads.remove(descriptor.id, raced)
        }
    }

    fun cancelDownload(modelId: String) {
        val activeDownload = activeDownloads[modelId] ?: return
        activeDownload.job.cancel(CancellationException("Download cancelled"))
        dataSource.cancel(activeDownload.url)
    }

    private suspend fun runDownload(
        descriptor: ModelDescriptor,
        url: String,
        expectedHash: String,
    ) {
        if (!networkPolicyProvider.isDownloadAllowed()) {
            throw DustCoreError.NetworkPolicyBlocked(
                "Current connection does not satisfy the active network policy",
            )
        }

        val availableBytes = maxOf(diskSpaceProvider.availableBytes(baseDir), 0L)
        val requiredBytes = if (descriptor.sizeBytes > (Long.MAX_VALUE / 2L)) {
            Long.MAX_VALUE
        } else {
            maxOf(descriptor.sizeBytes, 0L) * 2L
        }

        if (availableBytes < requiredBytes) {
            throw DustCoreError.StorageFull(
                "Available bytes: $availableBytes, required bytes: $requiredBytes",
            )
        }

        val modelDir = File(File(baseDir, "models"), descriptor.id)
        if (!modelDir.exists() && !modelDir.mkdirs()) {
            throw DustCoreError.DownloadFailed("Unable to create directory: ${modelDir.absolutePath}")
        }

        val partFile = File(modelDir, "${descriptor.id}.part")
        val finalFile = File(modelDir, "${descriptor.id}.bin")

        if (partFile.exists() && !partFile.delete()) {
            throw DustCoreError.DownloadFailed("Unable to clear partial file: ${partFile.absolutePath}")
        }

        stateStore.setStatus(descriptor.id, ModelStatus.Downloading(0f))

        val digest = MessageDigest.getInstance("SHA-256")
        var disclosedSize = maxOf(descriptor.sizeBytes, 0L)
        var totalBytesReceived = 0L
        var lastProgressEventBytes = 0L
        val coroutineContext = currentCoroutineContext()

        FileOutputStream(partFile).use { output ->
            dataSource.download(
                url = url,
                onPreflight = { preflight ->
                    disclosedSize = maxOf(preflight.contentLength ?: descriptor.sizeBytes, 0L)
                    eventEmitter(
                        "sizeDisclosure",
                        mapOf("modelId" to descriptor.id, "sizeBytes" to disclosedSize),
                    )
                },
                onChunk = { chunk ->
                    coroutineContext.ensureActive()

                    if (chunk.data.isNotEmpty()) {
                        output.write(chunk.data)
                        digest.update(chunk.data)
                    }

                    totalBytesReceived = chunk.totalBytesReceived
                    val denominator = maxOf(disclosedSize, descriptor.sizeBytes, 1L).toFloat()
                    val progress = minOf(totalBytesReceived.toFloat() / denominator, 1f)
                    stateStore.setStatus(descriptor.id, ModelStatus.Downloading(progress))

                    if (shouldEmitProgress(
                            currentBytes = totalBytesReceived,
                            lastEmittedBytes = lastProgressEventBytes,
                        )
                    ) {
                        emitProgress(
                            modelId = descriptor.id,
                            progress = progress,
                            bytesDownloaded = totalBytesReceived,
                            totalBytes = disclosedSize.takeIf { it > 0L },
                        )
                        lastProgressEventBytes = totalBytesReceived
                    }
                },
            )
            output.fd.sync()
        }

        if (totalBytesReceived > lastProgressEventBytes) {
            val denominator = maxOf(disclosedSize, descriptor.sizeBytes, 1L).toFloat()
            val progress = minOf(totalBytesReceived.toFloat() / denominator, 1f)
            emitProgress(
                modelId = descriptor.id,
                progress = progress,
                bytesDownloaded = totalBytesReceived,
                totalBytes = disclosedSize.takeIf { it > 0L },
            )
        }

        stateStore.setStatus(descriptor.id, ModelStatus.Verifying)

        val actualHash = digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        if (actualHash != expectedHash) {
            throw DustCoreError.VerificationFailed("Expected $expectedHash, received $actualHash")
        }

        if (finalFile.exists() && !finalFile.delete()) {
            throw DustCoreError.DownloadFailed("Unable to replace existing file: ${finalFile.absolutePath}")
        }

        if (!partFile.renameTo(finalFile)) {
            partFile.copyTo(finalFile, overwrite = true)
            if (partFile.exists()) {
                partFile.delete()
            }
        }

        stateStore.updateState(descriptor.id) {
            status = ModelStatus.Ready
            filePath = finalFile.absolutePath
        }
        eventEmitter(
            "modelReady",
            mapOf("modelId" to descriptor.id, "path" to finalFile.absolutePath),
        )
    }

    private fun handleFailure(descriptor: ModelDescriptor, error: Exception) {
        cleanupPartialFile(descriptor.id)

        if (error is CancellationException) {
            stateStore.setStatus(descriptor.id, ModelStatus.NotLoaded)
            return
        }

        val mlCoreError = when (error) {
            is DustCoreError -> error
            else -> DustCoreError.DownloadFailed(error.message)
        }

        stateStore.setStatus(descriptor.id, ModelStatus.Failed(mlCoreError))
        eventEmitter(
            "modelFailed",
            mapOf("modelId" to descriptor.id, "error" to mlCoreError.toMap()),
        )
    }

    private fun failImmediately(
        descriptor: ModelDescriptor,
        scope: CoroutineScope,
        error: DustCoreError,
    ): Job {
        stateStore.setStatus(descriptor.id, ModelStatus.Failed(error))
        eventEmitter(
            "modelFailed",
            mapOf("modelId" to descriptor.id, "error" to error.toMap()),
        )
        return scope.launch {}
    }

    private fun cleanupPartialFile(modelId: String) {
        val partFile = File(File(File(baseDir, "models"), modelId), "$modelId.part")
        if (partFile.exists()) {
            partFile.delete()
        }
    }

    fun cleanupStalePartFiles() {
        val modelsDir = File(baseDir, "models")
        val modelDirs = modelsDir.listFiles()?.filter { it.isDirectory } ?: return

        for (modelDir in modelDirs) {
            val partFiles = modelDir.listFiles()?.filter { it.isFile && it.extension == "part" } ?: continue
            if (partFiles.isEmpty()) {
                continue
            }

            for (partFile in partFiles) {
                partFile.delete()
            }

            val modelId = modelDir.name
            val finalFile = File(modelDir, "$modelId.bin")
            if (!finalFile.exists()) {
                stateStore.setStatus(modelId, ModelStatus.NotLoaded)
            }
        }
    }

    private fun shouldEmitProgress(currentBytes: Long, lastEmittedBytes: Long): Boolean {
        return currentBytes > lastEmittedBytes &&
            (currentBytes - lastEmittedBytes) >= PROGRESS_EVENT_INTERVAL_BYTES
    }

    private fun emitProgress(
        modelId: String,
        progress: Float,
        bytesDownloaded: Long,
        totalBytes: Long?,
    ) {
        val payload = mutableMapOf<String, Any?>(
            "modelId" to modelId,
            "progress" to progress.toDouble(),
            "bytesDownloaded" to bytesDownloaded,
        )
        totalBytes?.let { payload["totalBytes"] = it }
        eventEmitter("modelProgress", payload)
    }

    private data class ActiveDownload(
        val url: String,
        val job: Job,
    )
}
