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
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Thread-safe, write-once descriptor store.
 * Uses a dedicated [ReentrantReadWriteLock] — independent from [ModelStateStore]'s lock
 * so that descriptor reads never block during active downloads.
 */
class ModelRegistry {

    private val rwLock = ReentrantReadWriteLock()
    private val descriptors = mutableMapOf<String, ModelDescriptor>()

    /** Registers (or overwrites) a descriptor. Re-registration replaces the previous entry. */
    fun register(descriptor: ModelDescriptor) {
        val w = rwLock.writeLock()
        w.lock()
        try {
            descriptors[descriptor.id] = descriptor
        } finally {
            w.unlock()
        }
    }

    /** Returns the descriptor for [id], or null if not registered. */
    fun getDescriptor(id: String): ModelDescriptor? {
        val r = rwLock.readLock()
        r.lock()
        try {
            return descriptors[id]
        } finally {
            r.unlock()
        }
    }

    /** Returns all registered descriptors (snapshot copy). */
    fun allDescriptors(): List<ModelDescriptor> {
        val r = rwLock.readLock()
        r.lock()
        try {
            return descriptors.values.toList()
        } finally {
            r.unlock()
        }
    }
}
