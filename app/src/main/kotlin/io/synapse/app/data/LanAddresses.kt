package io.synapse.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * 取局域网 IPv4 地址，用于「局域网」那条接入地址。
 *
 * 不能只用 activeNetwork：开着代理/VPN 时它是 tun 网卡，拿到的是 172.19.0.1
 * 这类虚拟地址 —— 显示出来给用户复制，别的设备必然连不上。
 * 所以遍历全部网络，跳过 VPN，并按「以太网 > Wi-Fi > 蜂窝 > 其它」排序。
 */
object LanAddresses {

    fun current(context: Context): List<String> {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()

        return manager.allNetworks
            .mapNotNull { network ->
                val caps = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                // VPN / 代理建立的 tun 网卡不算局域网
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                val props = manager.getLinkProperties(network) ?: return@mapNotNull null
                val addresses = props.linkAddresses
                    .map(LinkAddress::getAddress)
                    .filterIsInstance<Inet4Address>()
                    .filterNot { it.isLoopbackAddress }
                    .mapNotNull { it.hostAddress }
                    .filter { it.isNotEmpty() }
                if (addresses.isEmpty()) return@mapNotNull null
                rank(caps) to addresses
            }
            // 物理网卡（以太网/Wi-Fi）排在前面，虚拟与未知类型靠后
            .sortedBy { it.first }
            .flatMap { it.second }
            .distinct()
    }

    /** 越小越优先；未识别的传输类型给一个大值排在最后。 */
    private fun rank(caps: NetworkCapabilities): Int = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 0
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
        else -> 3
    }
}
