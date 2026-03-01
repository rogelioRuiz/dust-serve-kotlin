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

import io.t6x.dust.core.ModelDescriptor
import io.t6x.dust.core.ModelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

class PostDownloadOrchestrator(
    private val probe: AcceleratorProbe,
    private val benchmark: DeviceBenchmark,
    private val descriptorProvider: (String) -> ModelDescriptor?,
    private val baseDir: File,
    private val scope: CoroutineScope,
) {

    fun onStatusChange(modelId: String, status: ModelStatus) {
        if (status != ModelStatus.Ready) {
            return
        }

        val descriptor = descriptorProvider(modelId) ?: return
        val modelPath = File(File(File(baseDir, "models"), descriptor.id), "${descriptor.id}.bin")

        scope.launch {
            try {
                val accelerator = probe.probe(descriptor.id, modelPath.absolutePath)
                benchmark.benchmark(
                    modelId = descriptor.id,
                    modelPath = modelPath.absolutePath,
                    accelerator = accelerator,
                )
            } catch (_: Exception) {
                // Probe and benchmark are best-effort and must not affect download success.
            }
        }
    }
}
