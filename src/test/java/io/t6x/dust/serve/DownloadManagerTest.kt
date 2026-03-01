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
import io.t6x.dust.core.ModelFormat
import io.t6x.dust.core.ModelStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadManagerTest {

    @Test
    fun downloadCompletesFileAtExpectedPath() = runTest {
        val modelId = "s2-t1"
        val data = makeData(3 * 1_048_576)
        val descriptor = makeDescriptor(modelId, data)
        val tempDir = makeTempDir()
        tempDir.deleteOnExit()

        val stateStore = ModelStateStore()
        val recorder = EventRecorder()
        val manager = DownloadManager(
            dataSource = MockDownloadDataSource(data = data, chunkSize = 1_048_576),
            stateStore = stateStore,
            networkPolicyProvider = MockNetworkPolicyProvider(true),
            diskSpaceProvider = MockDiskSpaceProvider(data.size.toLong() * 3L),
            baseDir = tempDir,
            eventEmitter = recorder::record,
        )

        manager.download(descriptor, this).join()

        val finalFile = File(File(File(tempDir, "models"), modelId), "$modelId.bin")
        assertTrue(finalFile.exists())
        assertArrayEquals(data, finalFile.readBytes())
        assertEquals(ModelStatus.Ready, stateStore.getStatus(modelId))

        tempDir.deleteRecursively()
    }

    @Test
    fun sha256VerificationPasses() = runTest {
        val data = makeData(3 * 1_048_576)
        val descriptor = makeDescriptor("s2-t2", data)
        val tempDir = makeTempDir()
        tempDir.deleteOnExit()

        val stateStore = ModelStateStore()
        val recorder = EventRecorder()
        val manager = DownloadManager(
            dataSource = MockDownloadDataSource(data = data),
            stateStore = stateStore,
            networkPolicyProvider = MockNetworkPolicyProvider(true),
            diskSpaceProvider = MockDiskSpaceProvider(data.size.toLong() * 3L),
            baseDir = tempDir,
            eventEmitter = recorder::record,
        )

        manager.download(descriptor, this).join()

        assertEquals(ModelStatus.Ready, stateStore.getStatus(descriptor.id))
        assertFalse(recorder.failedErrorCodes().contains("verificationFailed"))

        tempDir.deleteRecursively()
    }

    @Test
    fun sha256MismatchDeletesPartFile() = runTest {
        val data = makeData(2 * 1_048_576)
        val base = makeDescriptor("s2-t3", data)
        val descriptor = base.copy(sha256 = "0".repeat(64))
        val tempDir = makeTempDir()
        tempDir.deleteOnExit()

        val stateStore = ModelStateStore()
        val recorder = EventRecorder()
        val manager = DownloadManager(
            dataSource = MockDownloadDataSource(data = data),
            stateStore = stateStore,
            networkPolicyProvider = MockNetworkPolicyProvider(true),
            diskSpaceProvider = MockDiskSpaceProvider(data.size.toLong() * 3L),
            baseDir = tempDir,
            eventEmitter = recorder::record,
        )

        manager.download(descriptor, this).join()

        val partFile = File(File(File(tempDir, "models"), descriptor.id), "${descriptor.id}.part")
        assertFalse(partFile.exists())

        when (val status = stateStore.getStatus(descriptor.id)) {
            is ModelStatus.Failed -> assertTrue(status.error is DustCoreError.VerificationFailed)
            else -> fail("Expected failed status, got $status")
        }

        assertEquals(1, recorder.eventCount("modelFailed"))
        assertEquals(listOf("verificationFailed"), recorder.failedErrorCodes())

        tempDir.deleteRecursively()
    }

    @Test
    fun insufficientDiskSpaceRejected() = runTest {
        val data = makeData(1_024)
        val descriptor = makeDescriptor("s2-t4", data)
        val tempDir = makeTempDir()
        tempDir.deleteOnExit()

        val dataSource = MockDownloadDataSource(data = data)
        val stateStore = ModelStateStore()
        val manager = DownloadManager(
            dataSource = dataSource,
            stateStore = stateStore,
            networkPolicyProvider = MockNetworkPolicyProvider(true),
            diskSpaceProvider = MockDiskSpaceProvider(0L),
            baseDir = tempDir,
            eventEmitter = { _, _ -> },
        )

        manager.download(descriptor, this).join()

        assertEquals(0, dataSource.downloadCallCount)
        when (val status = stateStore.getStatus(descriptor.id)) {
            is ModelStatus.Failed -> assertTrue(status.error is DustCoreError.StorageFull)
            else -> fail("Expected failed status, got $status")
        }

        tempDir.deleteRecursively()
    }

    @Test
    fun sizeDisclosureBeforeProgress() = runTest {
        val data = makeData(3 * 1_048_576)
        val descriptor = makeDescriptor("s2-t5", data)
        val tempDir = makeTempDir()
        tempDir.deleteOnExit()

        val recorder = EventRecorder()
        val manager = DownloadManager(
            dataSource = MockDownloadDataSource(data = data, chunkSize = 1_048_576),
            stateStore = ModelStateStore(),
            networkPolicyProvider = MockNetworkPolicyProvider(true),
            diskSpaceProvider = MockDiskSpaceProvider(data.size.toLong() * 3L),
            baseDir = tempDir,
            eventEmitter = recorder::record,
        )

        manager.download(descriptor, this).join()

        val events = recorder.eventNames()
        val sizeIndex = events.indexOf("sizeDisclosure")
        val progressIndex = events.indexOf("modelProgress")
        assertTrue(sizeIndex >= 0)
        assertTrue(progressIndex >= 0)
        assertTrue(sizeIndex < progressIndex)

        tempDir.deleteRecursively()
    }

    @Test
    fun progressMonotonicallyIncreasing() = runTest {
        val data = makeData(5 * 1_048_576)
        val descriptor = makeDescriptor("s2-t6", data)
        val tempDir = makeTempDir()
        tempDir.deleteOnExit()

        val recorder = EventRecorder()
        val manager = DownloadManager(
            dataSource = MockDownloadDataSource(data = data, chunkSize = 1_048_576),
            stateStore = ModelStateStore(),
            networkPolicyProvider = MockNetworkPolicyProvider(true),
            diskSpaceProvider = MockDiskSpaceProvider(data.size.toLong() * 3L),
            baseDir = tempDir,
            eventEmitter = recorder::record,
        )

        manager.download(descriptor, this).join()

        val progressValues = recorder.progressValues()
        assertTrue(progressValues.size >= 3)
        for (index in 1 until progressValues.size) {
            assertTrue(progressValues[index] > progressValues[index - 1])
        }

        tempDir.deleteRecursively()
    }

    @Test
    fun statusTransitionsCorrectOrder() = runTest {
        val data = byteArrayOf(0x2A)
        val descriptor = makeDescriptor("s2-t7", data)
        val tempDir = makeTempDir()
        tempDir.deleteOnExit()

        val recorder = StatusTransitionRecorder()
        val stateStore = ModelStateStore { modelId, status ->
            if (modelId == descriptor.id) {
                recorder.record(status)
            }
        }
        val manager = DownloadManager(
            dataSource = MockDownloadDataSource(data = data, chunkSize = 1),
            stateStore = stateStore,
            networkPolicyProvider = MockNetworkPolicyProvider(true),
            diskSpaceProvider = MockDiskSpaceProvider(1_024L),
            baseDir = tempDir,
            eventEmitter = { _, _ -> },
        )

        manager.download(descriptor, this).join()

        assertEquals(
            listOf("downloading(0)", "downloading(>0)", "verifying", "ready"),
            recorder.labels(),
        )

        tempDir.deleteRecursively()
    }

    @Test
    fun concurrentDownloadsIdempotent() = runTest {
        val data = makeData(2 * 1_048_576)
        val descriptor = makeDescriptor("s2-t8", data)
        val tempDir = makeTempDir()
        tempDir.deleteOnExit()

        val dataSource = MockDownloadDataSource(data = data, chunkSize = 1_048_576)
        val recorder = EventRecorder()
        val manager = DownloadManager(
            dataSource = dataSource,
            stateStore = ModelStateStore(),
            networkPolicyProvider = MockNetworkPolicyProvider(true),
            diskSpaceProvider = MockDiskSpaceProvider(data.size.toLong() * 3L),
            baseDir = tempDir,
            eventEmitter = recorder::record,
        )

        val first = async { manager.download(descriptor, this).join() }
        val second = async { manager.download(descriptor, this).join() }
        first.await()
        second.await()

        assertEquals(1, dataSource.downloadCallCount)
        assertEquals(1, recorder.eventCount("modelReady"))

        tempDir.deleteRecursively()
    }

    @Test
    fun cancelDownloadRemovesPartFileAndResetsStatus() = runTest {
        val modelId = "s3-t4"
        val data = makeData(3 * 1_048_576)
        val descriptor = makeDescriptor(modelId, data)
        val tempDir = makeTempDir()
        tempDir.deleteOnExit()

        val stateStore = ModelStateStore()
        val manager = DownloadManager(
            dataSource = MockDownloadDataSource(
                data = data,
                chunkSize = 1_048_576,
                delayPerChunkMillis = 20L,
            ),
            stateStore = stateStore,
            networkPolicyProvider = MockNetworkPolicyProvider(true),
            diskSpaceProvider = MockDiskSpaceProvider(data.size.toLong() * 3L),
            baseDir = tempDir,
            eventEmitter = { _, _ -> },
        )

        val job = manager.download(descriptor, this)
        advanceTimeBy(25L)
        manager.cancelDownload(modelId)
        job.join()

        val partFile = File(File(File(tempDir, "models"), modelId), "$modelId.part")
        assertFalse(partFile.exists())
        assertEquals(ModelStatus.NotLoaded, stateStore.getStatus(modelId))

        tempDir.deleteRecursively()
    }

    @Test
    fun wifiOnlyBlocksCellularDownload() = runTest {
        val data = makeData(1_024)
        val descriptor = makeDescriptor("s3-t5", data)
        val tempDir = makeTempDir()
        tempDir.deleteOnExit()

        val dataSource = MockDownloadDataSource(data = data)
        val stateStore = ModelStateStore()
        val manager = DownloadManager(
            dataSource = dataSource,
            stateStore = stateStore,
            networkPolicyProvider = MockNetworkPolicyProvider(false),
            diskSpaceProvider = MockDiskSpaceProvider(10_000L),
            baseDir = tempDir,
            eventEmitter = { _, _ -> },
        )

        manager.download(descriptor, this).join()

        assertEquals(0, dataSource.downloadCallCount)
        when (val status = stateStore.getStatus(descriptor.id)) {
            is ModelStatus.Failed -> assertTrue(status.error is DustCoreError.NetworkPolicyBlocked)
            else -> fail("Expected failed status, got $status")
        }

        tempDir.deleteRecursively()
    }

    @Test
    fun wifiOnlyAllowsWifiDownload() = runTest {
        val data = makeData(1_024)
        val descriptor = makeDescriptor("s3-t6", data)
        val tempDir = makeTempDir()
        tempDir.deleteOnExit()

        val manager = DownloadManager(
            dataSource = MockDownloadDataSource(data = data, chunkSize = 512),
            stateStore = ModelStateStore(),
            networkPolicyProvider = MockNetworkPolicyProvider(true),
            diskSpaceProvider = MockDiskSpaceProvider(10_000L),
            baseDir = tempDir,
            eventEmitter = { _, _ -> },
        )

        manager.download(descriptor, this).join()

        val finalFile = File(File(File(tempDir, "models"), descriptor.id), "${descriptor.id}.bin")
        assertTrue(finalFile.exists())

        tempDir.deleteRecursively()
    }

    @Test
    fun stalePartFileCleanedOnLaunch() = runTest {
        val modelId = "s3-t7"
        val tempDir = makeTempDir()
        tempDir.deleteOnExit()

        val modelDir = File(File(tempDir, "models"), modelId)
        assertTrue(modelDir.mkdirs())
        val partFile = File(modelDir, "$modelId.part")
        partFile.writeBytes(byteArrayOf(0x01))

        val stateStore = ModelStateStore()
        stateStore.setStatus(modelId, ModelStatus.Downloading(0.5f))

        val manager = DownloadManager(
            dataSource = MockDownloadDataSource(data = byteArrayOf()),
            stateStore = stateStore,
            networkPolicyProvider = MockNetworkPolicyProvider(true),
            diskSpaceProvider = MockDiskSpaceProvider(10_000L),
            baseDir = tempDir,
            eventEmitter = { _, _ -> },
        )

        manager.cleanupStalePartFiles()

        assertFalse(partFile.exists())
        assertEquals(ModelStatus.NotLoaded, stateStore.getStatus(modelId))

        tempDir.deleteRecursively()
    }

    @Test
    fun retryOnTransientServerError() = runTest {
        val data = makeData(1_024)
        val descriptor = makeDescriptor("s3-t8", data)
        val tempDir = makeTempDir()
        tempDir.deleteOnExit()

        val dataSource = RetryingMockDownloadDataSource(
            failAttempts = 1,
            failError = MockDownloadError,
            data = data,
        )
        val stateStore = ModelStateStore()
        val manager = DownloadManager(
            dataSource = dataSource,
            stateStore = stateStore,
            networkPolicyProvider = MockNetworkPolicyProvider(true),
            diskSpaceProvider = MockDiskSpaceProvider(10_000L),
            baseDir = tempDir,
            eventEmitter = { _, _ -> },
        )

        manager.download(descriptor, this).join()
        when (val status = stateStore.getStatus(descriptor.id)) {
            is ModelStatus.Failed -> assertTrue(status.error is DustCoreError.DownloadFailed)
            else -> fail("Expected failed status after first attempt, got $status")
        }

        manager.download(descriptor, this).join()
        assertEquals(ModelStatus.Ready, stateStore.getStatus(descriptor.id))

        tempDir.deleteRecursively()
    }

    private fun makeDescriptor(modelId: String, data: ByteArray): ModelDescriptor {
        return ModelDescriptor(
            id = modelId,
            name = "Test Model $modelId",
            format = ModelFormat.GGUF,
            sizeBytes = data.size.toLong(),
            version = "1.0.0",
            url = "https://example.com/$modelId.bin",
            sha256 = sha256(data),
        )
    }

    private fun makeData(size: Int): ByteArray {
        return ByteArray(size) { index -> (index % 251).toByte() }
    }

    private fun sha256(data: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(data)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun makeTempDir(): File {
        return Files.createTempDirectory("download-manager-test").toFile()
    }
}

private class EventRecorder {
    private val lock = Any()
    private val names = mutableListOf<String>()
    private val progressValues = mutableListOf<Double>()
    private val failedCodes = mutableListOf<String>()

    fun record(name: String, payload: Map<String, Any?>) {
        synchronized(lock) {
            names += name

            if (name == "modelProgress") {
                (payload["progress"] as? Double)?.let { progressValues += it }
            }

            if (name == "modelFailed") {
                @Suppress("UNCHECKED_CAST")
                val error = payload["error"] as? Map<String, Any?>
                val code = error?.get("code") as? String
                if (!code.isNullOrBlank()) {
                    failedCodes += code
                }
            }
        }
    }

    fun eventNames(): List<String> = synchronized(lock) { names.toList() }

    fun progressValues(): List<Double> = synchronized(lock) { progressValues.toList() }

    fun failedErrorCodes(): List<String> = synchronized(lock) { failedCodes.toList() }

    fun eventCount(name: String): Int = synchronized(lock) { names.count { it == name } }
}

private class StatusTransitionRecorder {
    private val lock = Any()
    private val labels = mutableListOf<String>()

    fun record(status: ModelStatus) {
        synchronized(lock) {
            labels += when (status) {
                is ModelStatus.NotLoaded -> "notLoaded"
                is ModelStatus.Downloading -> if (status.progress == 0f) "downloading(0)" else "downloading(>0)"
                is ModelStatus.Verifying -> "verifying"
                is ModelStatus.Loading -> "loading"
                is ModelStatus.Ready -> "ready"
                is ModelStatus.Failed -> "failed"
                is ModelStatus.Unloading -> "unloading"
            }
        }
    }

    fun labels(): List<String> = synchronized(lock) { labels.toList() }
}
