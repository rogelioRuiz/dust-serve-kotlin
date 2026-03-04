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

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.t6x.dust.core.DustCoreError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class ModelDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val INPUT_MODEL_ID = "modelId"
        const val INPUT_URL = "url"
        const val INPUT_EXPECTED_HASH = "expectedHash"
        const val INPUT_BASE_DIR = "baseDir"
        const val INPUT_SIZE_BYTES = "sizeBytes"

        const val PROGRESS_BYTES_DOWNLOADED = "bytesDownloaded"
        const val PROGRESS_TOTAL_BYTES = "totalBytes"

        const val OUTPUT_PATH = "path"
        const val OUTPUT_ERROR_CODE = "errorCode"
        const val OUTPUT_ERROR_DETAIL = "errorDetail"

        private const val BUFFER_SIZE = 512 * 1024
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(INPUT_MODEL_ID)
            ?: return@withContext Result.failure(errorData(DustCoreError.DownloadFailed("Missing modelId")))
        val url = inputData.getString(INPUT_URL)
            ?: return@withContext Result.failure(errorData(DustCoreError.DownloadFailed("Missing URL")))
        val expectedHash = inputData.getString(INPUT_EXPECTED_HASH)?.lowercase(Locale.US)
            ?: return@withContext Result.failure(errorData(DustCoreError.VerificationFailed("Missing SHA-256 checksum")))
        val baseDirPath = inputData.getString(INPUT_BASE_DIR)
            ?: return@withContext Result.failure(errorData(DustCoreError.DownloadFailed("Missing base directory")))
        val sizeBytes = inputData.getLong(INPUT_SIZE_BYTES, 0L)

        val modelDir = File(File(baseDirPath, "models"), modelId)
        if (!modelDir.exists() && !modelDir.mkdirs()) {
            return@withContext Result.failure(
                errorData(DustCoreError.DownloadFailed("Unable to create directory: ${modelDir.absolutePath}")),
            )
        }

        val partFile = File(modelDir, "$modelId.part")
        val finalFile = File(modelDir, "$modelId.bin")

        DownloadNotificationHelper.ensureChannel(applicationContext)
        setForeground(makeForegroundInfo(modelId, 0, 0L, sizeBytes))

        try {
            var offset = partFile.takeIf { it.exists() }?.length() ?: 0L
            var connection = openConnection(url = url, offset = offset)

            try {
                var statusCode = connection.responseCode
                if (offset > 0L && statusCode == HttpURLConnection.HTTP_OK) {
                    connection.disconnect()
                    if (partFile.exists()) {
                        partFile.delete()
                    }
                    offset = 0L
                    connection = openConnection(url = url, offset = offset)
                    statusCode = connection.responseCode
                }

                if (statusCode in 500..599) {
                    return@withContext Result.retry()
                }

                if (statusCode !in 200..299) {
                    if (partFile.exists()) {
                        partFile.delete()
                    }
                    return@withContext Result.failure(
                        errorData(DustCoreError.DownloadFailed("HTTP $statusCode")),
                    )
                }

                var totalBytesDownloaded = if (offset > 0L && statusCode == HttpURLConnection.HTTP_PARTIAL) {
                    offset
                } else {
                    0L
                }
                var totalBytes = connection.contentLengthLong.takeIf { it >= 0L } ?: sizeBytes
                val append = offset > 0L && statusCode == HttpURLConnection.HTTP_PARTIAL

                if (append) {
                    if (totalBytes > 0L) {
                        totalBytes += totalBytesDownloaded
                    }
                } else if (partFile.exists() && !partFile.delete()) {
                    return@withContext Result.failure(
                        errorData(DustCoreError.DownloadFailed("Unable to reset partial file: ${partFile.absolutePath}")),
                    )
                }

                setProgress(workDataOf(
                    PROGRESS_BYTES_DOWNLOADED to totalBytesDownloaded,
                    PROGRESS_TOTAL_BYTES to totalBytes,
                ))

                RandomAccessFile(partFile, "rw").use { output ->
                    if (append) {
                        output.seek(output.length())
                    } else {
                        output.setLength(0L)
                    }

                    connection.inputStream.buffered().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)

                        while (true) {
                            if (isStopped) {
                                return@withContext Result.retry()
                            }

                            val read = input.read(buffer)
                            if (read < 0) {
                                break
                            }

                            output.write(buffer, 0, read)
                            totalBytesDownloaded += read.toLong()

                            setProgress(workDataOf(
                                PROGRESS_BYTES_DOWNLOADED to totalBytesDownloaded,
                                PROGRESS_TOTAL_BYTES to totalBytes,
                            ))

                            val progressPercent = if (totalBytes > 0L) {
                                ((totalBytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            setForeground(makeForegroundInfo(modelId, progressPercent, totalBytesDownloaded, totalBytes))
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }

            if (partFile.exists() && partFile.length() > 0L) {
                if (!verifyHash(partFile, expectedHash)) {
                    partFile.delete()
                    return@withContext Result.failure(
                        errorData(DustCoreError.VerificationFailed("SHA-256 mismatch")),
                    )
                }

                if (finalFile.exists() && !finalFile.delete()) {
                    return@withContext Result.failure(
                        errorData(DustCoreError.DownloadFailed("Unable to replace existing file: ${finalFile.absolutePath}")),
                    )
                }

                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    if (partFile.exists()) {
                        partFile.delete()
                    }
                }

                return@withContext Result.success(workDataOf(OUTPUT_PATH to finalFile.absolutePath))
            }

            return@withContext Result.failure(
                errorData(DustCoreError.DownloadFailed("Downloaded file was empty")),
            )
        } catch (error: Exception) {
            if (isStopped) {
                return@withContext Result.retry()
            }

            val isRetryable = error is java.io.IOException || error is java.net.SocketException
            if (isRetryable) {
                return@withContext Result.retry()
            }

            if (partFile.exists()) {
                partFile.delete()
            }

            return@withContext Result.failure(
                errorData(
                    when (error) {
                        is DustCoreError -> error
                        else -> DustCoreError.DownloadFailed(error.message)
                    },
                ),
            )
        }
    }

    private fun openConnection(
        url: String,
        offset: Long,
    ): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            if (offset > 0L) {
                setRequestProperty("Range", "bytes=$offset-")
            }
            connect()
        }
    }

    private fun verifyHash(file: File, expectedHash: String): Boolean {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        val actualHash = digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        return actualHash == expectedHash
    }

    private fun makeForegroundInfo(
        modelId: String,
        progress: Int,
        bytesDownloaded: Long,
        totalBytes: Long,
    ): ForegroundInfo {
        val notification = DownloadNotificationHelper.buildProgressNotification(
            applicationContext, modelId, progress, bytesDownloaded, totalBytes,
        )
        val notificationId = DownloadNotificationHelper.notificationId(modelId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun errorData(error: DustCoreError): Data {
        val detail = when (error) {
            is DustCoreError.DownloadFailed -> error.detail
            is DustCoreError.VerificationFailed -> error.detail
            else -> error.message
        }
        val code = when (error) {
            is DustCoreError.VerificationFailed -> "verificationFailed"
            else -> "downloadFailed"
        }
        return workDataOf(
            OUTPUT_ERROR_CODE to code,
            OUTPUT_ERROR_DETAIL to detail,
        )
    }
}
