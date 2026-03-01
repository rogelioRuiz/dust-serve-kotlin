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

import io.t6x.dust.core.DustInputTensor
import io.t6x.dust.core.DustOutputTensor
import io.t6x.dust.core.ModelSession
import io.t6x.dust.core.ModelStatus
import io.t6x.dust.core.SessionPriority
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MockModelSession(
    private val sessionPriority: SessionPriority = SessionPriority.INTERACTIVE,
    val sessionId: String = "",
    private val closeOrderRecorder: MutableList<String>? = null,
) : ModelSession {
    val predictCallCount = AtomicInteger(0)
    val predictRanOnMainThread = AtomicBoolean(false)
    val closeCallCount = AtomicInteger(0)

    override suspend fun predict(inputs: List<DustInputTensor>): List<DustOutputTensor> {
        predictCallCount.incrementAndGet()
        predictRanOnMainThread.set(Thread.currentThread().name.contains("main", ignoreCase = true))
        return emptyList()
    }

    override fun status(): ModelStatus = ModelStatus.Ready

    override fun priority(): SessionPriority = sessionPriority

    override suspend fun close() {
        closeCallCount.incrementAndGet()
        closeOrderRecorder?.add(sessionId)
    }
}
