package dev.local.catleaf.nativeapp.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 网络可用性（T22 独立验收 V02）：阅读详情需要实时知道在不在网，
 * 用来禁用「获取全文/新翻译」并说明原因——已保存正文、已缓存译文与摘要离线照常。
 * 只报告「有可用互联网能力」，不做测活请求。
 */
class NetworkMonitor(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    /** 任一已连通互联网的网络存在即为在线（不只看 activeNetwork：真机实测其可为 null） */
    @Suppress("DEPRECATION") // getAllNetworks 自 API 28 废弃但仍返回当前连接的网络；无公开替代可枚举
    fun isOnlineNow(): Boolean =
        cm?.allNetworks.orEmpty().any { network ->
            cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }

    /**
     * 初值 + 每次网络增减。用 registerNetworkCallback(request) 监听「有 INTERNET 能力的网络」的
     * onAvailable/onLost；onLost 后重新枚举判定（多网络时不能因单个丢失就报离线）。
     */
    val online: Flow<Boolean> = callbackFlow {
        trySend(isOnlineNow())
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(true) }
            override fun onLost(network: Network) { trySend(isOnlineNow()) }
        }
        try {
            cm?.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            // 极少数定制 ROM 拒绝注册时退化为初值（不崩；至少不谎报离线）
        }
        awaitClose { runCatching { cm?.unregisterNetworkCallback(callback) } }
    }
}
