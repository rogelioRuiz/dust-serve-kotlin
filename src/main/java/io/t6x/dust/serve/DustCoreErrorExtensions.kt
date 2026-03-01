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

fun DustCoreError.toMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    when (this) {
        is DustCoreError.ModelNotFound -> map["code"] = "modelNotFound"
        is DustCoreError.ModelNotReady -> map["code"] = "modelNotReady"
        is DustCoreError.ModelCorrupted -> map["code"] = "modelCorrupted"
        is DustCoreError.FormatUnsupported -> map["code"] = "formatUnsupported"
        is DustCoreError.SessionClosed -> map["code"] = "sessionClosed"
        is DustCoreError.SessionLimitReached -> map["code"] = "sessionLimitReached"
        is DustCoreError.InvalidInput -> { map["code"] = "invalidInput"; detail?.let { map["detail"] = it } }
        is DustCoreError.InferenceFailed -> { map["code"] = "inferenceFailed"; detail?.let { map["detail"] = it } }
        is DustCoreError.MemoryExhausted -> map["code"] = "memoryExhausted"
        is DustCoreError.DownloadFailed -> { map["code"] = "downloadFailed"; detail?.let { map["detail"] = it } }
        is DustCoreError.StorageFull -> { map["code"] = "storageFull"; detail?.let { map["detail"] = it } }
        is DustCoreError.NetworkPolicyBlocked -> { map["code"] = "networkPolicyBlocked"; detail?.let { map["detail"] = it } }
        is DustCoreError.VerificationFailed -> { map["code"] = "verificationFailed"; detail?.let { map["detail"] = it } }
        is DustCoreError.Cancelled -> map["code"] = "cancelled"
        is DustCoreError.Timeout -> map["code"] = "timeout"
        is DustCoreError.ServiceNotRegistered -> { map["code"] = "serviceNotRegistered"; map["serviceName"] = serviceName }
        is DustCoreError.UnknownError -> { map["code"] = "unknownError"; errorMessage?.let { map["message"] = it } }
    }
    return map
}
