package com.zhixu.studyos.core.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** 网络可用性抽象，便于在测试中模拟离线。 */
interface AiConnectivity {
    fun isOnline(): Boolean
}

class AndroidAiConnectivity(context: Context) : AiConnectivity {
    private val manager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun isOnline(): Boolean {
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
