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
import kotlin.math.abs

class AcceleratorProbe(
    private val engine: ProbeInferenceEngine,
    private val store: ProbeResultStore,
) {

    suspend fun probe(modelId: String, modelPath: String): Accelerator {
        store.getCachedAccelerator(modelId)?.let { return it }

        val inputs = dummyInputs()
        val cpuBaseline = engine.runInference(
            modelPath = modelPath,
            accelerator = Accelerator.CPU,
            inputs = inputs,
        )

        for (candidate in listOf(Accelerator.NNAPI, Accelerator.GPU)) {
            val outputs = try {
                engine.runInference(
                    modelPath = modelPath,
                    accelerator = candidate,
                    inputs = inputs,
                )
            } catch (_: Exception) {
                continue
            }

            if (maxDiff(cpuBaseline, outputs) < 1e-3f) {
                store.cacheAccelerator(modelId, candidate)
                return candidate
            }
        }

        store.cacheAccelerator(modelId, Accelerator.CPU)
        return Accelerator.CPU
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

    private fun maxDiff(
        baseline: List<DustOutputTensor>,
        candidate: List<DustOutputTensor>,
    ): Float {
        if (baseline.size != candidate.size) {
            return Float.MAX_VALUE
        }

        var maxDiff = 0f
        for (index in baseline.indices) {
            val baselineTensor = baseline[index]
            val candidateTensor = candidate[index]
            if (baselineTensor.shape != candidateTensor.shape) {
                return Float.MAX_VALUE
            }
            if (baselineTensor.data.size != candidateTensor.data.size) {
                return Float.MAX_VALUE
            }

            for (valueIndex in baselineTensor.data.indices) {
                maxDiff = maxOf(
                    maxDiff,
                    abs(baselineTensor.data[valueIndex] - candidateTensor.data[valueIndex]),
                )
            }
        }

        return maxDiff
    }
}
