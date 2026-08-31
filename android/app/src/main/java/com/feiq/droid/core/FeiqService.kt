package com.feiq.droid.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * 前台服务：维持 App 在线（安卓 8+ 后台无法长跑 socket）。
 * 持有组播锁，保证能收到局域网广播。
 */
class FeiqService : Service() {

    companion object {
        private const val CHANNEL_ID = "feiq_online"
        private const val NOTIF_ID = 1
        const val EXTRA_NICK = "nick"
        const val EXTRA_GROUP = "group"

        fun start(ctx: Context, nick: String, group: String) {
            val i = Intent(ctx, FeiqService::class.java)
                .putExtra(EXTRA_NICK, nick).putExtra(EXTRA_GROUP, group)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, FeiqService::class.java))
        }
    }

    private var multicastLock: WifiManager.MulticastLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private var currentNick = "飞秋安卓"
    private var currentGroup = ""
    private var restartScheduled = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        multicastLock = NetworkInfo.acquireMulticastLock(this, "feiq-mc")
        currentNick = intent?.getStringExtra(EXTRA_NICK) ?: "飞秋安卓"
        currentGroup = intent?.getStringExtra(EXTRA_GROUP) ?: ""
        App.startEngine(this, currentNick, currentGroup)
        registerNetworkCallback()
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        handler.removeCallbacksAndMessages(null)
        multicastLock?.let { runCatching { it.release() } }
        App.stopEngine()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "飞秋在线服务", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "维持局域网在线状态" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("飞秋安卓版")
            .setContentText("正在局域网在线")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setOngoing(true)
            .build()

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleEngineRestart()
            override fun onLost(network: Network) = scheduleEngineRestart()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                scheduleEngineRestart()
        }
        networkCallback = cb
        runCatching {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(req, cb)
        }.onFailure {
            runCatching { cm.registerDefaultNetworkCallback(cb) }
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
        networkCallback = null
    }

    private fun scheduleEngineRestart() {
        if (restartScheduled) return
        restartScheduled = true
        handler.postDelayed({
            restartScheduled = false
            multicastLock?.let { runCatching { it.release() } }
            multicastLock = NetworkInfo.acquireMulticastLock(this, "feiq-mc")
            if (App.isStarted()) App.engine().restartNetwork()
            else App.startEngine(this, currentNick, currentGroup)
        }, 1200)
    }
}
