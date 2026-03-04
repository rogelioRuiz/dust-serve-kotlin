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
import io.t6x.dust.core.ModelSessionFactory
import io.t6x.dust.core.ModelStatus
import io.t6x.dust.core.SessionPriority
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class MemoryPressureLevel {
    STANDARD,
    CRITICAL,
}

class SessionManager(
    private val stateStore: ModelStateStore,
    private var factory: ModelSessionFactory,
) {

    internal val inferenceDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val lock = ReentrantLock()
    private val cachedSessions = mutableMapOf<String, CachedSession>()

    /**
     * Swaps the session factory. Must be called before any sessions are loaded
     * (i.e. while [cachedSessions] is empty).
     */
    fun setFactory(newFactory: ModelSessionFactory) {
        lock.withLock {
            check(cachedSessions.isEmpty()) { "Cannot swap factory while sessions are active" }
            factory = newFactory
        }
    }

    suspend fun loadModel(descriptor: ModelDescriptor, priority: SessionPriority): ModelSession {
        incrementCachedRefCount(descriptor.id)?.let { return it }

        val state = stateStore.getState(descriptor.id) ?: throw DustCoreError.ModelNotFound
        if (state.status != ModelStatus.Ready) {
            throw DustCoreError.ModelNotReady
        }

        val createdSession = factory.makeSession(descriptor, priority)

        var installedSession: ModelSession? = null
        var installedRefCount = 0
        var discardedSession: ModelSession? = null

        lock.withLock {
            val cached = cachedSessions[descriptor.id]
            if (cached != null) {
                cached.refCount += 1
                cached.lastAccessTime = System.nanoTime()
                installedSession = cached.session
                installedRefCount = cached.refCount
                discardedSession = createdSession
            } else {
                val wrappedSession = QueuedModelSession(createdSession, inferenceDispatcher)
                cachedSessions[descriptor.id] = CachedSession(
                    session = wrappedSession,
                    priority = priority,
                    refCount = 1,
                    lastAccessTime = System.nanoTime(),
                )
                installedSession = wrappedSession
                installedRefCount = 1
            }
        }

        discardedSession?.let {
            try {
                it.close()
            } catch (_: Throwable) {
            }
        }

        updateRefCount(descriptor.id, installedRefCount)
        return installedSession ?: createdSession
    }

    suspend fun unloadModel(id: String) {
        val nextRefCount = lock.withLock {
            val cached = cachedSessions[id]
            if (cached == null || cached.refCount == 0) {
                null
            } else {
                cached.refCount -= 1
                cached.refCount
            }
        } ?: throw DustCoreError.ModelNotFound

        updateRefCount(id, nextRefCount)
    }

    suspend fun evict(id: String) {
        val cachedSession = lock.withLock {
            cachedSessions.remove(id)?.session
        }

        if (cachedSession != null) {
            updateRefCount(id, 0)
            cachedSession.close()
        }
    }

    suspend fun evictUnderPressure(level: MemoryPressureLevel) {
        val evicted = lock.withLock {
            val eligible = cachedSessions.filter { (_, cached) ->
                cached.refCount == 0 && when (level) {
                    MemoryPressureLevel.STANDARD -> cached.priority == SessionPriority.BACKGROUND
                    MemoryPressureLevel.CRITICAL -> true
                }
            }
            val sorted = eligible.entries.sortedBy { it.value.lastAccessTime }
            val evictedSessions = sorted.map { it.key to it.value.session }
            for ((id, _) in evictedSessions) {
                cachedSessions.remove(id)
            }
            evictedSessions
        }

        for ((id, session) in evicted) {
            updateRefCount(id, 0)
            try {
                session.close()
            } catch (_: Throwable) {
            }
        }
    }

    fun refCount(id: String): Int = lock.withLock {
        cachedSessions[id]?.refCount ?: 0
    }

    fun hasCachedSession(id: String): Boolean = lock.withLock {
        cachedSessions.containsKey(id)
    }

    private fun incrementCachedRefCount(id: String): ModelSession? {
        var cachedSession: ModelSession? = null
        var refCount = 0

        lock.withLock {
            val cached = cachedSessions[id]
            if (cached != null) {
                cached.refCount += 1
                cached.lastAccessTime = System.nanoTime()
                cachedSession = cached.session
                refCount = cached.refCount
            }
        }

        if (cachedSession != null) {
            updateRefCount(id, refCount)
        }

        return cachedSession
    }

    private fun updateRefCount(id: String, refCount: Int) {
        stateStore.updateState(id) {
            this.refCount = refCount
        }
    }
}

private data class CachedSession(
    val session: ModelSession,
    val priority: SessionPriority,
    var refCount: Int,
    var lastAccessTime: Long,
)

private class QueuedModelSession(
    private val delegate: ModelSession,
    private val inferenceDispatcher: CoroutineDispatcher,
) : ModelSession {
    override suspend fun predict(inputs: List<DustInputTensor>): List<DustOutputTensor> {
        return withContext(inferenceDispatcher) {
            delegate.predict(inputs)
        }
    }

    override fun status(): ModelStatus = delegate.status()

    override fun priority(): SessionPriority = delegate.priority()

    override suspend fun close() {
        delegate.close()
    }
}
