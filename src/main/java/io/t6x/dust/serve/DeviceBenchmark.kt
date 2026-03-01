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

class DeviceBenchmark(
    private val engine: ProbeInferenceEngine,
    private val store: ProbeResultStore,
    private val clock: BenchmarkClock = SystemBenchmarkClock,
) {

    suspend fun benchmark(
        modelId: String,
        modelPath: String,
        accelerator: Accelerator,
    ): DeviceTier {
        if (store.isBenchmarkComplete()) {
            return requireNotNull(store.getDeviceTier()) {
                "Benchmark marked complete without a stored device tier"
            }
        }

        val timings = mutableListOf<Long>()
        repeat(5) {
            val startedAt = clock.nowMs()
            engine.runInference(
                modelPath = modelPath,
                accelerator = accelerator,
                inputs = dummyInputs(),
            )
            timings += (clock.nowMs() - startedAt)
        }

        val medianMs = timings.sorted()[2]
        val tier = when {
            medianMs < 50L -> DeviceTier.FAST
            medianMs < 100L -> DeviceTier.MID
            else -> DeviceTier.SLOW
        }

        store.setDeviceTier(tier)
        store.setBenchmarkComplete()
        return tier
    }

    private fun dummyInputs(): List<DustInputTensor> {
        return listOf(
            DustInputTensor(
                name = "input",
                data = listOf(0f),
                shape = listOf(1),
            ),
        )
    }
}

interface BenchmarkClock {
    fun nowMs(): Long
}

object SystemBenchmarkClock : BenchmarkClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
