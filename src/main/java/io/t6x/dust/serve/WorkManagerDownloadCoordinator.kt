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

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.t6x.dust.core.DustCoreError
import io.t6x.dust.core.ModelDescriptor
import io.t6x.dust.core.ModelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class WorkManagerDownloadCoordinator(
    private val workManager: WorkManager,
    private val scope: CoroutineScope,
    private val stateStore: ModelStateStore,
    private val baseDir: File,
    private val networkPolicyProvider: NetworkPolicyProvider,
    private val isWifiOnlyEnabled: () -> Boolean,
    private val eventEmitter: (String, Map<String, Any?>) -> Unit,
) {
    private val activeWorkIds = ConcurrentHashMap<String, UUID>()

    companion object {
        const val COMMON_TAG = "dust-serve-download"
    }

    fun download(descriptor: ModelDescriptor) {
        val url = descriptor.url
        if (url.isNullOrBlank()) {
            failImmediately(
                descriptor = descriptor,
                error = DustCoreError.DownloadFailed("Model descriptor is missing a valid download URL"),
            )
            return
        }

        val expectedHash = descriptor.sha256?.lowercase(Locale.US)
        if (expectedHash.isNullOrBlank()) {
            failImmediately(
                descriptor = descriptor,
                error = DustCoreError.VerificationFailed("Model descriptor is missing a SHA-256 checksum"),
            )
            return
        }

        if (!networkPolicyProvider.isDownloadAllowed()) {
            failImmediately(
                descriptor = descriptor,
                error = DustCoreError.NetworkPolicyBlocked("Current connection does not satisfy the active network policy"),
            )
            return
        }

        if (activeWorkIds.containsKey(descriptor.id)) {
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (isWifiOnlyEnabled()) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    ModelDownloadWorker.INPUT_MODEL_ID to descriptor.id,
                    ModelDownloadWorker.INPUT_URL to url,
                    ModelDownloadWorker.INPUT_EXPECTED_HASH to expectedHash,
                    ModelDownloadWorker.INPUT_BASE_DIR to baseDir.absolutePath,
                    ModelDownloadWorker.INPUT_SIZE_BYTES to descriptor.sizeBytes,
                ),
            )
            .addTag(descriptor.id)
            .addTag(COMMON_TAG)
            .build()

        if (activeWorkIds.putIfAbsent(descriptor.id, request.id) != null) {
            return
        }

        stateStore.setStatus(descriptor.id, ModelStatus.Downloading(0f))
        eventEmitter(
            "sizeDisclosure",
            mapOf("modelId" to descriptor.id, "sizeBytes" to descriptor.sizeBytes),
        )

        workManager.enqueueUniqueWork(descriptor.id, ExistingWorkPolicy.KEEP, request)
        observeWork(descriptor = descriptor, workId = request.id)
    }

    fun cancelDownload(modelId: String) {
        val workId = activeWorkIds.remove(modelId) ?: return
        workManager.cancelWorkById(workId)
    }

    private fun observeWork(
        descriptor: ModelDescriptor,
        workId: UUID,
    ) {
        scope.launch {
            var lastProgressBytes = -1L

            workManager.getWorkInfoByIdFlow(workId).collectLatest { workInfo ->
                if (workInfo == null) {
                    return@collectLatest
                }

                when (workInfo.state) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.RUNNING,
                    WorkInfo.State.BLOCKED -> {
                        val bytesDownloaded = workInfo.progress.getLong(ModelDownloadWorker.PROGRESS_BYTES_DOWNLOADED, 0L)
                        val totalBytes = workInfo.progress.getLong(
                            ModelDownloadWorker.PROGRESS_TOTAL_BYTES,
                            descriptor.sizeBytes,
                        )

                        if (bytesDownloaded > lastProgressBytes) {
                            lastProgressBytes = bytesDownloaded
                            val denominator = maxOf(totalBytes, descriptor.sizeBytes, 1L).toFloat()
                            val progress = minOf(bytesDownloaded.toFloat() / denominator, 1f)
                            stateStore.setStatus(descriptor.id, ModelStatus.Downloading(progress))
                            eventEmitter(
                                "modelProgress",
                                mapOf(
                                    "modelId" to descriptor.id,
                                    "progress" to progress.toDouble(),
                                    "bytesDownloaded" to bytesDownloaded,
                                    "totalBytes" to totalBytes.takeIf { it > 0L },
                                ),
                            )
                        }
                    }

                    WorkInfo.State.SUCCEEDED -> {
                        activeWorkIds.remove(descriptor.id, workId)
                        val filePath = workInfo.outputData.getString(ModelDownloadWorker.OUTPUT_PATH)
                        stateStore.updateState(descriptor.id) {
                            status = ModelStatus.Ready
                            this.filePath = filePath
                        }
                        eventEmitter(
                            "modelReady",
                            mapOf(
                                "modelId" to descriptor.id,
                                "path" to filePath,
                            ),
                        )
                    }

                    WorkInfo.State.FAILED -> {
                        activeWorkIds.remove(descriptor.id, workId)
                        val error = errorFrom(workInfo.outputData)
                        stateStore.setStatus(descriptor.id, ModelStatus.Failed(error))
                        eventEmitter(
                            "modelFailed",
                            mapOf("modelId" to descriptor.id, "error" to error.toMap()),
                        )
                    }

                    WorkInfo.State.CANCELLED -> {
                        activeWorkIds.remove(descriptor.id, workId)
                        stateStore.setStatus(descriptor.id, ModelStatus.NotLoaded)
                    }
                }
            }
        }
    }

    fun isActive(modelId: String): Boolean = activeWorkIds.containsKey(modelId)

    fun reconnectActiveDownloads(
        descriptorProvider: (String) -> ModelDescriptor?,
    ): Set<String> {
        val activeIds = mutableSetOf<String>()
        val workInfos = try {
            workManager.getWorkInfosByTag(COMMON_TAG).get()
        } catch (_: Exception) {
            return activeIds
        }

        for (workInfo in workInfos) {
            if (workInfo.state != WorkInfo.State.RUNNING && workInfo.state != WorkInfo.State.ENQUEUED) {
                continue
            }
            val modelId = workInfo.tags.firstOrNull { it != COMMON_TAG && it != "ModelDownloadWorker" }
                ?: continue
            if (activeWorkIds.putIfAbsent(modelId, workInfo.id) != null) {
                continue
            }
            activeIds.add(modelId)
            val descriptor = descriptorProvider(modelId) ?: continue
            stateStore.setStatus(descriptor.id, ModelStatus.Downloading(0f))
            observeWork(descriptor = descriptor, workId = workInfo.id)
        }
        return activeIds
    }

    private fun failImmediately(
        descriptor: ModelDescriptor,
        error: DustCoreError,
    ) {
        stateStore.setStatus(descriptor.id, ModelStatus.Failed(error))
        eventEmitter(
            "modelFailed",
            mapOf("modelId" to descriptor.id, "error" to error.toMap()),
        )
    }

    private fun errorFrom(data: androidx.work.Data): DustCoreError {
        val code = data.getString(ModelDownloadWorker.OUTPUT_ERROR_CODE)
        val detail = data.getString(ModelDownloadWorker.OUTPUT_ERROR_DETAIL)
        return when (code) {
            "verificationFailed" -> DustCoreError.VerificationFailed(detail)
            else -> DustCoreError.DownloadFailed(detail)
        }
    }
}
