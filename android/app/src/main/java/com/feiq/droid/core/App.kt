package com.feiq.droid.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/** 全局引擎+消息仓库持有者（首版用单例；后续可换 DI）。 */
object App {
    @Volatile private var engine: FeiqEngine? = null
    @Volatile private var repo: MessageRepository? = null
    private var scope: CoroutineScope? = null

    fun engine(): FeiqEngine = engine ?: error("Engine 未启动")
    fun repo(): MessageRepository = repo ?: error("Repo 未启动")
    fun isStarted() = engine != null

    fun startEngine(ctx: Context, nick: String, group: String): FeiqEngine {
        engine?.let { return it }
        val appCtx = ctx.applicationContext
        val protocolName = sanitize(nick).ifBlank { "android" }
        val identity = FeiqEngine.Identity(
            user = protocolName,
            host = protocolName,
            nick = nick,
            group = group,
            pseudoMac = NetworkInfo.pseudoMac(appCtx),
            portProvider = { Prefs.port(appCtx) },
        )
        val eng = FeiqEngine(identity)
        engine = eng
        eng.start()
        // 常驻消息仓库：无论 UI 在哪，都接收并持久化消息
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = sc
        repo = MessageRepository(appCtx, eng, sc).also { it.start() }
        return eng
    }

    fun stopEngine() {
        engine?.stop()
        scope?.coroutineContext?.get(Job)?.cancel()
        engine = null
        repo = null
        scope = null
    }

    /** 用户名/主机名里不能含 ':'，替换掉。 */
    private fun sanitize(s: String) = s.replace(":", ";").trim()
}
