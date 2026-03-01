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
import io.t6x.dust.core.ModelDescriptor
import io.t6x.dust.core.ModelSession
import io.t6x.dust.core.SessionPriority

interface ModelSessionFactory {
    suspend fun makeSession(descriptor: ModelDescriptor, priority: SessionPriority): ModelSession
}

class StubModelSessionFactory : ModelSessionFactory {
    override suspend fun makeSession(descriptor: ModelDescriptor, priority: SessionPriority): ModelSession {
        throw DustCoreError.FormatUnsupported
    }
}

class StubProbeInferenceEngine : ProbeInferenceEngine {
    override suspend fun runInference(
        modelPath: String,
        accelerator: Accelerator,
        inputs: List<DustInputTensor>,
    ): List<DustOutputTensor> {
        throw DustCoreError.FormatUnsupported
    }
}
