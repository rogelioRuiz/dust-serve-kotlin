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

import android.content.SharedPreferences

interface ProbeResultStore {
    fun getCachedAccelerator(modelId: String): Accelerator?
    fun cacheAccelerator(modelId: String, accelerator: Accelerator)
    fun getDeviceTier(): DeviceTier?
    fun setDeviceTier(tier: DeviceTier)
    fun isBenchmarkComplete(): Boolean
    fun setBenchmarkComplete()
}

class SharedPreferencesProbeResultStore(
    private val preferences: SharedPreferences,
) : ProbeResultStore {

    companion object {
        const val PREFERENCES_NAME = "io.t6x.dust.serve.probe"

        private const val ACCELERATOR_PREFIX = "accelerator_"
        private const val DEVICE_TIER_KEY = "device_tier"
        private const val BENCHMARK_COMPLETE_KEY = "benchmark_complete"
    }

    override fun getCachedAccelerator(modelId: String): Accelerator? {
        val rawValue = preferences.getString("$ACCELERATOR_PREFIX$modelId", null) ?: return null
        return rawValue.toEnumOrNull<Accelerator>()
    }

    override fun cacheAccelerator(modelId: String, accelerator: Accelerator) {
        preferences.edit().putString("$ACCELERATOR_PREFIX$modelId", accelerator.name).apply()
    }

    override fun getDeviceTier(): DeviceTier? {
        val rawValue = preferences.getString(DEVICE_TIER_KEY, null) ?: return null
        return rawValue.toEnumOrNull<DeviceTier>()
    }

    override fun setDeviceTier(tier: DeviceTier) {
        preferences.edit().putString(DEVICE_TIER_KEY, tier.name).apply()
    }

    override fun isBenchmarkComplete(): Boolean {
        return preferences.getBoolean(BENCHMARK_COMPLETE_KEY, false)
    }

    override fun setBenchmarkComplete() {
        preferences.edit().putBoolean(BENCHMARK_COMPLETE_KEY, true).apply()
    }
}

class InMemoryProbeResultStore : ProbeResultStore {
    private val accelerators = mutableMapOf<String, Accelerator>()

    @Volatile
    private var deviceTier: DeviceTier? = null

    @Volatile
    private var benchmarkComplete = false

    @Synchronized
    override fun getCachedAccelerator(modelId: String): Accelerator? = accelerators[modelId]

    @Synchronized
    override fun cacheAccelerator(modelId: String, accelerator: Accelerator) {
        accelerators[modelId] = accelerator
    }

    override fun getDeviceTier(): DeviceTier? = deviceTier

    override fun setDeviceTier(tier: DeviceTier) {
        deviceTier = tier
    }

    override fun isBenchmarkComplete(): Boolean = benchmarkComplete

    override fun setBenchmarkComplete() {
        benchmarkComplete = true
    }
}

private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? {
    return try {
        enumValueOf<T>(this)
    } catch (_: IllegalArgumentException) {
        null
    }
}
