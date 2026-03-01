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

import android.os.StatFs
import io.t6x.dust.core.DustCoreError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

class HttpDownloadDataSource(
    private val chunkSizeBytes: Int = 512 * 1024,
) : DownloadDataSource {
    private val activeConnections = ConcurrentHashMap<String, HttpURLConnection>()
    private val cancelledUrls = ConcurrentHashMap.newKeySet<String>()

    override suspend fun download(
        url: String,
        onPreflight: (DownloadPreflightInfo) -> Unit,
        onChunk: (DownloadChunk) -> Unit,
    ) = withContext(Dispatchers.IO) {
        cancelledUrls.remove(url)

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
        }

        activeConnections[url] = connection

        try {
            coroutineContext.ensureActive()
            if (cancelledUrls.contains(url)) {
                throw CancellationException("Download cancelled")
            }

            connection.connect()
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                throw DustCoreError.DownloadFailed("HTTP $statusCode")
            }

            val contentLength = connection.contentLengthLong.takeIf { it >= 0L }
            onPreflight(DownloadPreflightInfo(contentLength = contentLength))

            connection.inputStream.buffered().use { input ->
                val buffer = ByteArray(chunkSizeBytes)
                var totalBytesReceived = 0L

                while (true) {
                    coroutineContext.ensureActive()
                    if (cancelledUrls.contains(url)) {
                        throw CancellationException("Download cancelled")
                    }

                    val bytesRead = input.read(buffer)
                    if (bytesRead < 0) {
                        break
                    }

                    totalBytesReceived += bytesRead.toLong()
                    onChunk(DownloadChunk(buffer.copyOf(bytesRead), totalBytesReceived))
                }
            }
        } catch (error: Exception) {
            if (cancelledUrls.contains(url) || error is InterruptedIOException) {
                throw CancellationException("Download cancelled", error)
            }
            throw error
        } finally {
            activeConnections.remove(url)
            cancelledUrls.remove(url)
            connection.disconnect()
        }
    }

    override fun cancel(url: String) {
        cancelledUrls.add(url)
        activeConnections.remove(url)?.disconnect()
    }
}

class SystemDiskSpaceProvider : DiskSpaceProvider {
    override fun availableBytes(path: File): Long {
        return try {
            StatFs(path.absolutePath).availableBytes
        } catch (_: IllegalArgumentException) {
            0L
        }
    }
}
