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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

object MockDownloadError : RuntimeException("Injected mock failure")

class MockDownloadDataSource(
    private val data: ByteArray,
    private val chunkSize: Int = 512 * 1024,
    private val preflightContentLength: Long? = data.size.toLong(),
    private val failureMode: FailureMode = FailureMode.None,
    private val delayPerChunkMillis: Long = 0L,
) : DownloadDataSource {

    sealed interface FailureMode {
        data object None : FailureMode
        data class Immediate(val error: Throwable) : FailureMode
        data class AfterBytes(val byteCount: Long, val error: Throwable) : FailureMode
    }

    private val callCount = AtomicInteger(0)
    private val cancelledUrls = mutableSetOf<String>()
    private val cancellationLock = Any()

    val downloadCallCount: Int
        get() = callCount.get()

    override suspend fun download(
        url: String,
        onPreflight: (DownloadPreflightInfo) -> Unit,
        onChunk: (DownloadChunk) -> Unit,
    ) {
        callCount.incrementAndGet()
        synchronized(cancellationLock) {
            cancelledUrls.remove(url)
        }

        when (failureMode) {
            is FailureMode.Immediate -> throw failureMode.error
            FailureMode.None -> Unit
            is FailureMode.AfterBytes -> Unit
        }

        onPreflight(DownloadPreflightInfo(contentLength = preflightContentLength))

        var offset = 0
        var totalBytesReceived = 0L
        while (offset < data.size) {
            coroutineContext.ensureActive()
            if (isCancelled(url)) {
                throw CancellationException("Download cancelled")
            }

            val end = minOf(offset + chunkSize, data.size)
            val chunk = data.copyOfRange(offset, end)

            if (delayPerChunkMillis > 0) {
                delay(delayPerChunkMillis)
            }

            totalBytesReceived += chunk.size.toLong()
            onChunk(DownloadChunk(chunk, totalBytesReceived))

            if (failureMode is FailureMode.AfterBytes && totalBytesReceived >= failureMode.byteCount) {
                throw failureMode.error
            }

            offset = end
        }
    }

    override fun cancel(url: String) {
        synchronized(cancellationLock) {
            cancelledUrls.add(url)
        }
    }

    private fun isCancelled(url: String): Boolean {
        return synchronized(cancellationLock) {
            cancelledUrls.contains(url)
        }
    }
}

class MockDiskSpaceProvider(
    private val bytesAvailable: Long,
) : DiskSpaceProvider {
    override fun availableBytes(path: java.io.File): Long = bytesAvailable
}

class MockNetworkPolicyProvider(
    private val allowed: Boolean,
) : NetworkPolicyProvider {
    override fun isDownloadAllowed(): Boolean = allowed
}

class RetryingMockDownloadDataSource(
    private val failAttempts: Int,
    private val failError: Throwable,
    private val data: ByteArray,
    private val chunkSize: Int = 512 * 1024,
) : DownloadDataSource {
    private val attemptCount = AtomicInteger(0)

    override suspend fun download(
        url: String,
        onPreflight: (DownloadPreflightInfo) -> Unit,
        onChunk: (DownloadChunk) -> Unit,
    ) {
        val attempt = attemptCount.incrementAndGet()
        if (attempt <= failAttempts) {
            throw failError
        }

        onPreflight(DownloadPreflightInfo(contentLength = data.size.toLong()))

        var offset = 0
        var totalBytesReceived = 0L
        while (offset < data.size) {
            coroutineContext.ensureActive()

            val end = minOf(offset + chunkSize, data.size)
            val chunk = data.copyOfRange(offset, end)
            totalBytesReceived += chunk.size.toLong()
            onChunk(DownloadChunk(chunk, totalBytesReceived))
            offset = end
        }
    }

    override fun cancel(url: String) = Unit
}
