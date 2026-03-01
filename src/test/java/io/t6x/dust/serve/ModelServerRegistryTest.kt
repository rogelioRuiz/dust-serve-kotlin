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

import io.t6x.dust.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ModelServerRegistryTest {

    @Before
    fun setUp() {
        DustCoreRegistry.resetForTesting()
    }

    @After
    fun tearDown() {
        DustCoreRegistry.resetForTesting()
    }

    // ── S1-T1: Register and retrieve descriptor — all fields match ───────────

    @Test
    fun registerAndRetrieveDescriptor() {
        val registry = ModelRegistry()
        val descriptor = ModelDescriptor(
            id = "qwen3-0.6b",
            name = "Qwen3 0.6B Instruct",
            format = ModelFormat.GGUF,
            sizeBytes = 350_000_000L,
            version = "1.0.0",
            quantization = "Q4_K_M",
            metadata = mapOf("source" to "huggingface", "family" to "qwen3"),
        )

        registry.register(descriptor)
        val retrieved = registry.getDescriptor("qwen3-0.6b")

        assertNotNull(retrieved)
        assertEquals("qwen3-0.6b", retrieved!!.id)
        assertEquals("Qwen3 0.6B Instruct", retrieved.name)
        assertEquals(ModelFormat.GGUF, retrieved.format)
        assertEquals(350_000_000L, retrieved.sizeBytes)
        assertEquals("1.0.0", retrieved.version)
        assertEquals("Q4_K_M", retrieved.quantization)
        assertEquals("huggingface", retrieved.metadata?.get("source"))
        assertEquals("qwen3", retrieved.metadata?.get("family"))
    }

    // ── S1-T2: Unknown model returns NotLoaded — not null, not error ─────────

    @Test
    fun unknownModelReturnsNotLoaded() {
        val stateStore = ModelStateStore()
        val status = stateStore.getStatus("ghost")
        assertEquals(ModelStatus.NotLoaded, status)
    }

    // ── S1-T3: listDescriptors returns all registered ────────────────────────

    @Test
    fun listDescriptorsReturnsAll() {
        val registry = ModelRegistry()
        val ids = listOf("model-a", "model-b", "model-c")
        for (id in ids) {
            registry.register(
                ModelDescriptor(
                    id = id, name = id, format = ModelFormat.GGUF,
                    sizeBytes = 100L, version = "1.0",
                ),
            )
        }

        val all = registry.allDescriptors()
        assertEquals(3, all.size)
        val retrievedIds = all.map { it.id }.toSet()
        assertEquals(ids.toSet(), retrievedIds)
    }

    // ── S1-T4: Re-registration overwrites ────────────────────────────────────

    @Test
    fun reRegistrationOverwrites() {
        val registry = ModelRegistry()
        val a = ModelDescriptor(
            id = "m1", name = "Model A", format = ModelFormat.GGUF,
            sizeBytes = 100L, version = "1.0",
        )
        val b = ModelDescriptor(
            id = "m1", name = "Model B", format = ModelFormat.ONNX,
            sizeBytes = 200L, version = "2.0",
        )

        registry.register(a)
        registry.register(b)

        val retrieved = registry.getDescriptor("m1")
        assertEquals("Model B", retrieved!!.name)
        assertEquals(ModelFormat.ONNX, retrieved.format)
        assertEquals(200L, retrieved.sizeBytes)
        assertEquals("2.0", retrieved.version)
    }

    // ── S1-T5: Descriptors and states independently stored ───────────────────

    @Test
    fun descriptorsAndStatesIndependent() {
        val registry = ModelRegistry()
        val stateStore = ModelStateStore()

        val descriptor = ModelDescriptor(
            id = "m1", name = "Original", format = ModelFormat.GGUF,
            sizeBytes = 500L, version = "1.0",
        )
        registry.register(descriptor)
        stateStore.setStatus("m1", ModelStatus.NotLoaded)

        // Mutate state — descriptor must remain unchanged
        stateStore.setStatus("m1", ModelStatus.Downloading(0.5f))

        val retrievedDescriptor = registry.getDescriptor("m1")
        assertEquals("Original", retrievedDescriptor!!.name)
        assertEquals(500L, retrievedDescriptor.sizeBytes)

        val retrievedStatus = stateStore.getStatus("m1")
        assertTrue(retrievedStatus is ModelStatus.Downloading)
        assertEquals(0.5f, (retrievedStatus as ModelStatus.Downloading).progress)
    }

    // ── S1-T6: DustCoreRegistry resolves ModelServer after registration ────────

    @Test
    fun mlCoreRegistryResolves() {
        val server = TestModelServer()
        DustCoreRegistry.getInstance().registerModelServer(server)

        val resolved = DustCoreRegistry.getInstance().resolveModelServer()
        assertNotNull(resolved)
        assertSame(server, resolved)
    }

    // ── S1-T8: 100 threads concurrent registration — no crash/deadlock ───────

    @Test
    fun concurrentRegistrationNoCrash() {
        val registry = ModelRegistry()
        val threadCount = 100
        val barrier = CyclicBarrier(threadCount)
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val error = AtomicReference<Throwable?>()

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    barrier.await(5, TimeUnit.SECONDS)
                    registry.register(
                        ModelDescriptor(
                            id = "model-$i", name = "Model $i", format = ModelFormat.GGUF,
                            sizeBytes = i.toLong() * 100, version = "1.0",
                        ),
                    )
                } catch (t: Throwable) {
                    error.compareAndSet(null, t)
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Timed out", latch.await(10, TimeUnit.SECONDS))
        assertNull("Thread error: ${error.get()}", error.get())
        assertEquals(threadCount, registry.allDescriptors().size)
        executor.shutdownNow()
    }

    @Test
    fun concurrentStateUpdateNoCrash() {
        val stateStore = ModelStateStore()
        val threadCount = 100
        val barrier = CyclicBarrier(threadCount)
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val error = AtomicReference<Throwable?>()

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    barrier.await(5, TimeUnit.SECONDS)
                    stateStore.setStatus("model-$i", ModelStatus.Downloading(i.toFloat() / threadCount))
                    stateStore.getStatus("model-$i")
                } catch (t: Throwable) {
                    error.compareAndSet(null, t)
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Timed out", latch.await(10, TimeUnit.SECONDS))
        assertNull("Thread error: ${error.get()}", error.get())
        executor.shutdownNow()
    }

    // ── Test helper ──────────────────────────────────────────────────────────

    private class TestModelServer : ModelServer {
        override suspend fun loadModel(descriptor: ModelDescriptor, priority: SessionPriority): ModelSession {
            throw DustCoreError.FormatUnsupported
        }
        override suspend fun unloadModel(id: String) {
            throw DustCoreError.ModelNotFound
        }
        override suspend fun listModels() = emptyList<ModelDescriptor>()
        override suspend fun modelStatus(id: String) = ModelStatus.NotLoaded
    }
}
