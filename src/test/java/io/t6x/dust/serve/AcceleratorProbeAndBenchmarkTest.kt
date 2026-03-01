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

import io.t6x.dust.core.DustOutputTensor
import io.t6x.dust.core.ModelDescriptor
import io.t6x.dust.core.ModelFormat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private class SplitOutputEngine(
    private val cpuOutput: List<DustOutputTensor>,
    private val delegateOutput: Map<Accelerator, List<DustOutputTensor>> = emptyMap(),
    private val throwOn: Set<Accelerator> = emptySet(),
) : ProbeInferenceEngine {
    val callCount = AtomicInteger(0)
    val accelerators = CopyOnWriteArrayList<Accelerator>()

    override suspend fun runInference(
        modelPath: String,
        accelerator: Accelerator,
        inputs: List<io.t6x.dust.core.DustInputTensor>,
    ): List<DustOutputTensor> {
        callCount.incrementAndGet()
        accelerators += accelerator

        if (accelerator in throwOn) {
            throw RuntimeException("Synthetic failure for $accelerator")
        }

        return when (accelerator) {
            Accelerator.CPU -> cpuOutput
            else -> delegateOutput[accelerator] ?: cpuOutput
        }
    }
}

private class ControlledClock(vararg values: Long) : BenchmarkClock {
    private val sequence = values.toList()
    private var index = 0

    override fun nowMs(): Long {
        check(index < sequence.size) { "ControlledClock exhausted" }
        return sequence[index++]
    }
}

class AcceleratorProbeAndBenchmarkTest {

    @Test
    fun probeSelectsAndCachesValidAccelerator() = runTest {
        val store = InMemoryProbeResultStore()
        val engine = SplitOutputEngine(
            cpuOutput = tensorOf(1f, 2f),
            delegateOutput = mapOf(
                Accelerator.NNAPI to tensorOf(1f, 2f),
            ),
        )
        val probe = AcceleratorProbe(engine = engine, store = store)

        val selected = probe.probe("m1", "/tmp/m1.bin")

        assertEquals(Accelerator.NNAPI, selected)
        assertEquals(Accelerator.NNAPI, store.getCachedAccelerator("m1"))
        assertEquals(listOf(Accelerator.CPU, Accelerator.NNAPI), engine.accelerators)
    }

    @Test
    fun probeRejectsWrongOutput() = runTest {
        val store = InMemoryProbeResultStore()
        val engine = SplitOutputEngine(
            cpuOutput = tensorOf(1f, 2f),
            delegateOutput = mapOf(
                Accelerator.NNAPI to tensorOf(1.01f, 2f),
                Accelerator.GPU to tensorOf(1f, 2.01f),
            ),
        )
        val probe = AcceleratorProbe(engine = engine, store = store)

        val selected = probe.probe("m1", "/tmp/m1.bin")

        assertEquals(Accelerator.CPU, selected)
        assertEquals(Accelerator.CPU, store.getCachedAccelerator("m1"))
        assertEquals(listOf(Accelerator.CPU, Accelerator.NNAPI, Accelerator.GPU), engine.accelerators)
    }

    @Test
    fun probeSkipsThrowingAccelerator() = runTest {
        val store = InMemoryProbeResultStore()
        val engine = SplitOutputEngine(
            cpuOutput = tensorOf(1f, 2f),
            delegateOutput = mapOf(
                Accelerator.GPU to tensorOf(1f, 2f),
            ),
            throwOn = setOf(Accelerator.NNAPI),
        )
        val probe = AcceleratorProbe(engine = engine, store = store)

        val selected = probe.probe("m1", "/tmp/m1.bin")

        assertEquals(Accelerator.GPU, selected)
        assertEquals(Accelerator.GPU, store.getCachedAccelerator("m1"))
        assertEquals(listOf(Accelerator.CPU, Accelerator.NNAPI, Accelerator.GPU), engine.accelerators)
    }

    @Test
    fun probeRunsOnlyOncePerModel() = runTest {
        val store = InMemoryProbeResultStore()
        val engine = SplitOutputEngine(
            cpuOutput = tensorOf(1f),
            delegateOutput = mapOf(
                Accelerator.NNAPI to tensorOf(1f),
            ),
        )
        val probe = AcceleratorProbe(engine = engine, store = store)

        val first = probe.probe("m1", "/tmp/m1.bin")
        val second = probe.probe("m1", "/tmp/m1.bin")

        assertEquals(Accelerator.NNAPI, first)
        assertEquals(Accelerator.NNAPI, second)
        assertEquals(2, engine.callCount.get())
    }

    @Test
    fun benchmarkProducesValidDeviceTier() = runTest {
        val store = InMemoryProbeResultStore()
        val engine = SplitOutputEngine(cpuOutput = tensorOf(1f))
        val benchmark = DeviceBenchmark(
            engine = engine,
            store = store,
            clock = ControlledClock(0, 30, 30, 60, 60, 90, 90, 120, 120, 150),
        )

        val tier = benchmark.benchmark("m1", "/tmp/m1.bin", Accelerator.CPU)

        assertEquals(DeviceTier.FAST, tier)
        assertTrue(store.isBenchmarkComplete())
        assertEquals(5, engine.callCount.get())
    }

    @Test
    fun benchmarkMedianCalculationIsCorrect() = runTest {
        val store = InMemoryProbeResultStore()
        val engine = SplitOutputEngine(cpuOutput = tensorOf(1f))
        val benchmark = DeviceBenchmark(
            engine = engine,
            store = store,
            clock = ControlledClock(0, 80, 80, 140, 140, 210, 210, 300, 300, 365),
        )

        val tier = benchmark.benchmark("m1", "/tmp/m1.bin", Accelerator.CPU)

        assertEquals(DeviceTier.MID, tier)
    }

    @Test
    fun deviceTierAccessibleViaStoreAfterBenchmark() = runTest {
        val store = InMemoryProbeResultStore()
        val engine = SplitOutputEngine(cpuOutput = tensorOf(1f))
        val benchmark = DeviceBenchmark(
            engine = engine,
            store = store,
            clock = ControlledClock(0, 120, 120, 240, 240, 360, 360, 480, 480, 600),
        )

        assertNull(store.getDeviceTier())

        val tier = benchmark.benchmark("m1", "/tmp/m1.bin", Accelerator.CPU)

        assertEquals(DeviceTier.SLOW, tier)
        assertEquals(DeviceTier.SLOW, store.getDeviceTier())
    }

    @Test
    fun benchmarkRunsOnlyOncePerInstall() = runTest {
        val store = InMemoryProbeResultStore()
        val engine = SplitOutputEngine(cpuOutput = tensorOf(1f))
        val benchmark = DeviceBenchmark(
            engine = engine,
            store = store,
            clock = ControlledClock(0, 30, 30, 60, 60, 90, 90, 120, 120, 150),
        )

        val first = benchmark.benchmark("m1", "/tmp/m1.bin", Accelerator.CPU)
        val second = benchmark.benchmark("m2", "/tmp/m2.bin", Accelerator.GPU)

        assertEquals(DeviceTier.FAST, first)
        assertEquals(DeviceTier.FAST, second)
        assertEquals(5, engine.callCount.get())
    }

    private fun tensorOf(vararg values: Float): List<DustOutputTensor> {
        return listOf(
            DustOutputTensor(
                name = "output",
                data = values.toList(),
                shape = listOf(values.size),
            ),
        )
    }
}
