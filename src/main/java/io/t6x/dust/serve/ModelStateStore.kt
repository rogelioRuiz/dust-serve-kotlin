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

import io.t6x.dust.core.ModelStatus
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Mutable model state — tracks status throughout the lifecycle.
 */
data class ModelState(
    var status: ModelStatus = ModelStatus.NotLoaded,
    var filePath: String? = null,
    var refCount: Int = 0,
)

/**
 * Thread-safe state store with its own [ReentrantReadWriteLock], independent from [ModelRegistry].
 * State writes happen constantly during download and session events;
 * keeping a separate lock prevents descriptor reads from blocking.
 */
class ModelStateStore(
    private val onStatusChange: ((String, ModelStatus) -> Unit)? = null,
) {

    private val rwLock = ReentrantReadWriteLock()
    private val states = mutableMapOf<String, ModelState>()

    /**
     * Returns the current status for [id]. Returns [ModelStatus.NotLoaded] if unknown —
     * never null, never an error (S1-T2).
     */
    fun getStatus(id: String): ModelStatus {
        val r = rwLock.readLock()
        r.lock()
        try {
            return states[id]?.status ?: ModelStatus.NotLoaded
        } finally {
            r.unlock()
        }
    }

    /** Returns the current state snapshot for [id], or null if unknown. */
    fun getState(id: String): ModelState? {
        val r = rwLock.readLock()
        r.lock()
        try {
            return states[id]?.copy()
        } finally {
            r.unlock()
        }
    }

    /** Atomically updates the state for [id], creating a default entry if needed. */
    fun updateState(id: String, transform: ModelState.() -> Unit): ModelState {
        val updatedState: ModelState
        val statusChangedTo: ModelStatus?

        val w = rwLock.writeLock()
        w.lock()
        try {
            val current = states[id]
            val next = current?.copy() ?: ModelState()
            next.transform()
            states[id] = next
            updatedState = next.copy()
            statusChangedTo = if (current?.status != updatedState.status) updatedState.status else null
        } finally {
            w.unlock()
        }

        statusChangedTo?.let { onStatusChange?.invoke(id, it) }
        return updatedState
    }

    /** Sets the status for a given model ID. */
    fun setStatus(id: String, status: ModelStatus) {
        updateState(id) {
            this.status = status
        }
    }
}
