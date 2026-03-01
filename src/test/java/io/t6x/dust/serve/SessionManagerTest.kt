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
import io.t6x.dust.core.ModelDescriptor
import io.t6x.dust.core.ModelFormat
import io.t6x.dust.core.ModelSession
import io.t6x.dust.core.ModelStatus
import io.t6x.dust.core.SessionPriority
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private class MockModelSessionFactory(
    private val shouldThrow: DustCoreError? = null,
    private val delayMillis: Long = 0,
    private val closeOrderRecorder: MutableList<String>? = null,
) : ModelSessionFactory {
    val createCount = AtomicInteger(0)
    val sessions = CopyOnWriteArrayList<MockModelSession>()

    override suspend fun makeSession(descriptor: ModelDescriptor, priority: SessionPriority): ModelSession {
        if (delayMillis > 0) {
            delay(delayMillis)
        }

        shouldThrow?.let { throw it }

        createCount.incrementAndGet()
        return MockModelSession(
            sessionPriority = priority,
            sessionId = descriptor.id,
            closeOrderRecorder = closeOrderRecorder,
        ).also { sessions.add(it) }
    }
}

class SessionManagerTest {

    @Test
    fun loadModelReturnsSessionForReadyModel() = runTest {
        val stateStore = readyStateStore()
        val factory = MockModelSessionFactory()
        val manager = SessionManager(stateStore = stateStore, factory = factory)

        val session = manager.loadModel(descriptor(), SessionPriority.INTERACTIVE)

        assertEquals(ModelStatus.Ready, session.status())
        assertEquals(SessionPriority.INTERACTIVE, session.priority())
        assertEquals(1, factory.createCount.get())
    }

    @Test
    fun loadModelNonReadyThrowsModelNotReady() = runTest {
        val stateStore = ModelStateStore().apply {
            setStatus("model-a", ModelStatus.NotLoaded)
        }
        val manager = SessionManager(stateStore = stateStore, factory = MockModelSessionFactory())

        try {
            manager.loadModel(descriptor(), SessionPriority.INTERACTIVE)
            fail("Expected ModelNotReady")
        } catch (error: DustCoreError) {
            assertTrue(error is DustCoreError.ModelNotReady)
        }
    }

    @Test
    fun loadModelUnknownThrowsModelNotFound() = runTest {
        val manager = SessionManager(stateStore = ModelStateStore(), factory = MockModelSessionFactory())

        try {
            manager.loadModel(descriptor(), SessionPriority.INTERACTIVE)
            fail("Expected ModelNotFound")
        } catch (error: DustCoreError) {
            assertTrue(error is DustCoreError.ModelNotFound)
        }
    }

    @Test
    fun secondLoadModelReturnsCachedSession() = runTest {
        val stateStore = readyStateStore()
        val factory = MockModelSessionFactory()
        val manager = SessionManager(stateStore = stateStore, factory = factory)

        val first = manager.loadModel(descriptor(), SessionPriority.INTERACTIVE)
        val second = manager.loadModel(descriptor(), SessionPriority.BACKGROUND)

        assertSame(first, second)
        assertEquals(1, factory.createCount.get())
    }

    @Test
    fun refCountIncrementsOnLoad() = runTest {
        val stateStore = readyStateStore()
        val manager = SessionManager(stateStore = stateStore, factory = MockModelSessionFactory())

        manager.loadModel(descriptor(), SessionPriority.INTERACTIVE)
        manager.loadModel(descriptor(), SessionPriority.INTERACTIVE)
        manager.loadModel(descriptor(), SessionPriority.INTERACTIVE)

        assertEquals(3, manager.refCount("model-a"))
        assertEquals(3, stateStore.getState("model-a")?.refCount)
    }

    @Test
    fun refCountDecrementsOnUnload() = runTest {
        val stateStore = readyStateStore()
        val manager = SessionManager(stateStore = stateStore, factory = MockModelSessionFactory())

        manager.loadModel(descriptor(), SessionPriority.INTERACTIVE)
        manager.loadModel(descriptor(), SessionPriority.INTERACTIVE)
        manager.loadModel(descriptor(), SessionPriority.INTERACTIVE)
        manager.unloadModel("model-a")
        manager.unloadModel("model-a")

        assertEquals(1, manager.refCount("model-a"))
        assertEquals(1, stateStore.getState("model-a")?.refCount)
    }

    @Test
    fun refCountZeroSessionStillCached() = runTest {
        val stateStore = readyStateStore()
        val manager = SessionManager(stateStore = stateStore, factory = MockModelSessionFactory())

        manager.loadModel(descriptor(), SessionPriority.INTERACTIVE)
        manager.unloadModel("model-a")

        assertEquals(0, manager.refCount("model-a"))
        assertTrue(manager.hasCachedSession("model-a"))
        assertEquals(0, stateStore.getState("model-a")?.refCount)
    }

    @Test
    fun reloadAfterEviction() = runTest {
        val stateStore = readyStateStore()
        val factory = MockModelSessionFactory()
        val manager = SessionManager(stateStore = stateStore, factory = factory)

        val first = manager.loadModel(descriptor(), SessionPriority.INTERACTIVE)
        manager.unloadModel("model-a")
        manager.evict("model-a")
        val second = manager.loadModel(descriptor(), SessionPriority.INTERACTIVE)

        assertNotSame(first, second)
        assertEquals(2, factory.createCount.get())
    }

    @Test
    fun predictRunsOnInferenceQueue() = runTest {
        val factory = MockModelSessionFactory()
        val manager = SessionManager(stateStore = readyStateStore(), factory = factory)

        val session = manager.loadModel(descriptor(), SessionPriority.INTERACTIVE)
        session.predict(emptyList())

        val createdSession = factory.sessions.first()
        assertEquals(1, createdSession.predictCallCount.get())
        assertFalse(createdSession.predictRanOnMainThread.get())
    }

    @Test
    fun concurrentLoadModelReturnsSameSession() = runTest {
        val factory = MockModelSessionFactory(delayMillis = 20)
        val manager = SessionManager(stateStore = readyStateStore(), factory = factory)

        val sessions = (0 until 20).map {
            async {
                manager.loadModel(descriptor(), SessionPriority.INTERACTIVE)
            }
        }.awaitAll()

        val first = sessions.first()
        assertTrue(sessions.all { it === first })
        assertEquals(20, manager.refCount("model-a"))
        assertEquals(20, factory.createCount.get())
    }

    @Test
    fun backgroundZeroRefsEvictedOnStandard() = runTest {
        val stateStore = readyStateStore()
        val factory = MockModelSessionFactory()
        val manager = SessionManager(stateStore = stateStore, factory = factory)

        manager.loadModel(descriptor(), SessionPriority.BACKGROUND)
        manager.unloadModel("model-a")

        manager.evictUnderPressure(MemoryPressureLevel.STANDARD)

        assertFalse(manager.hasCachedSession("model-a"))
        val createdSession = factory.sessions.first()
        assertEquals(1, createdSession.closeCallCount.get())
    }

    @Test
    fun backgroundWithRefsNotEvicted() = runTest {
        val stateStore = readyStateStore()
        val factory = MockModelSessionFactory()
        val manager = SessionManager(stateStore = stateStore, factory = factory)

        manager.loadModel(descriptor(), SessionPriority.BACKGROUND)

        manager.evictUnderPressure(MemoryPressureLevel.STANDARD)

        assertTrue(manager.hasCachedSession("model-a"))
        val createdSession = factory.sessions.first()
        assertEquals(0, createdSession.closeCallCount.get())
    }

    @Test
    fun interactiveNotEvictedOnStandard() = runTest {
        val stateStore = readyStateStore()
        val factory = MockModelSessionFactory()
        val manager = SessionManager(stateStore = stateStore, factory = factory)

        manager.loadModel(descriptor(), SessionPriority.INTERACTIVE)
        manager.unloadModel("model-a")

        manager.evictUnderPressure(MemoryPressureLevel.STANDARD)

        assertTrue(manager.hasCachedSession("model-a"))
        val createdSession = factory.sessions.first()
        assertEquals(0, createdSession.closeCallCount.get())
    }

    @Test
    fun interactiveEvictedOnCritical() = runTest {
        val stateStore = readyStateStore()
        val factory = MockModelSessionFactory()
        val manager = SessionManager(stateStore = stateStore, factory = factory)

        manager.loadModel(descriptor(), SessionPriority.INTERACTIVE)
        manager.unloadModel("model-a")

        manager.evictUnderPressure(MemoryPressureLevel.CRITICAL)

        assertFalse(manager.hasCachedSession("model-a"))
        val createdSession = factory.sessions.first()
        assertEquals(1, createdSession.closeCallCount.get())
    }

    @Test
    fun backgroundWithRefsNotEvictedOnCritical() = runTest {
        val stateStore = readyStateStore()
        val factory = MockModelSessionFactory()
        val manager = SessionManager(stateStore = stateStore, factory = factory)

        manager.loadModel(descriptor(), SessionPriority.BACKGROUND)

        manager.evictUnderPressure(MemoryPressureLevel.CRITICAL)

        assertTrue(manager.hasCachedSession("model-a"))
        val createdSession = factory.sessions.first()
        assertEquals(0, createdSession.closeCallCount.get())
    }

    @Test
    fun reAcquireAfterEvictionReloads() = runTest {
        val stateStore = readyStateStore()
        val factory = MockModelSessionFactory()
        val manager = SessionManager(stateStore = stateStore, factory = factory)

        val first = manager.loadModel(descriptor(), SessionPriority.BACKGROUND)
        manager.unloadModel("model-a")
        manager.evictUnderPressure(MemoryPressureLevel.STANDARD)
        val second = manager.loadModel(descriptor(), SessionPriority.BACKGROUND)

        assertNotSame(first, second)
        assertEquals(ModelStatus.Ready, stateStore.getStatus("model-a"))
        assertEquals(2, factory.createCount.get())
    }

    @Test
    fun lruEvictionOrder() = runTest {
        val closeOrder = CopyOnWriteArrayList<String>()
        val stateStore = readyStateStore("model-a", "model-b", "model-c")
        val factory = MockModelSessionFactory(closeOrderRecorder = closeOrder)
        val manager = SessionManager(stateStore = stateStore, factory = factory)

        manager.loadModel(descriptor("model-a"), SessionPriority.BACKGROUND)
        Thread.sleep(1)
        manager.loadModel(descriptor("model-b"), SessionPriority.BACKGROUND)
        Thread.sleep(1)
        manager.loadModel(descriptor("model-c"), SessionPriority.BACKGROUND)

        manager.unloadModel("model-a")
        manager.unloadModel("model-b")
        manager.unloadModel("model-c")

        Thread.sleep(1)
        manager.loadModel(descriptor("model-a"), SessionPriority.BACKGROUND)
        manager.unloadModel("model-a")

        manager.evictUnderPressure(MemoryPressureLevel.STANDARD)

        assertEquals(listOf("model-b", "model-c", "model-a"), closeOrder)
    }

    private fun readyStateStore(vararg ids: String): ModelStateStore {
        val resolvedIds = if (ids.isEmpty()) arrayOf("model-a") else ids
        return ModelStateStore().apply {
            resolvedIds.forEach { id ->
                setStatus(id, ModelStatus.Ready)
            }
        }
    }

    private fun descriptor(id: String = "model-a"): ModelDescriptor {
        return ModelDescriptor(
            id = id,
            name = "Model A",
            format = ModelFormat.GGUF,
            sizeBytes = 1_024L,
            version = "1.0.0",
        )
    }
}
