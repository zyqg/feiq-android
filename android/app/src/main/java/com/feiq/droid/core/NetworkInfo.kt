package com.feiq.droid.core

import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface

/** 取本机网络信息：IP、子网广播地址、稳定伪 MAC。 */
object NetworkInfo {

    /** 本机 WiFi IPv4。 */
    fun localIp(): String? {
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * 子网定向广播地址（如 192.168.2.255）。
     * 很多企业网禁 255.255.255.255，子网广播更可靠。
     */
    fun subnetBroadcast(): String? {
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val b = ia.broadcast
                    if (b is Inet4Address) return b.hostAddress
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /** 稳定伪 MAC（12 位大写 HEX），用 androidId 派生，仿飞秋版本段。 */
    fun pseudoMac(ctx: Context): String {
        val id = try {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) { null } ?: "FEIQDROID000"
        val hex = id.filter { it.isLetterOrDigit() }.uppercase().padEnd(12, '0').take(12)
        return hex.map { if (it in "0123456789ABCDEF") it else 'A' }.joinToString("")
    }

    /** 取组播锁（收广播必需，部分机型不取收不到 255.255.255.255）。 */
    fun acquireMulticastLock(ctx: Context, tag: String): WifiManager.MulticastLock? {
        return try {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi.createMulticastLock(tag).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) { null }
    }
}
