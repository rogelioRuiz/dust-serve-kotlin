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
import io.t6x.dust.core.DustInputTensor
import io.t6x.dust.core.DustOutputTensor

// ModelSessionFactory interface is defined in dust-core.

class StubProbeInferenceEngine : ProbeInferenceEngine {
    override suspend fun runInference(
        modelPath: String,
        accelerator: Accelerator,
        inputs: List<DustInputTensor>,
    ): List<DustOutputTensor> {
        throw DustCoreError.FormatUnsupported
    }
}
