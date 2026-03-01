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

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

interface NetworkPolicyProvider {
    fun isDownloadAllowed(): Boolean
}

class SystemNetworkPolicyProvider(
    context: Context,
) : NetworkPolicyProvider {
    companion object {
        const val PREFERENCES_NAME = "io.t6x.dust.serve"
        const val WIFI_ONLY_KEY = "wifiOnly"
    }

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun isDownloadAllowed(): Boolean {
        if (!preferences.getBoolean(WIFI_ONLY_KEY, false)) {
            return true
        }

        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }
}
