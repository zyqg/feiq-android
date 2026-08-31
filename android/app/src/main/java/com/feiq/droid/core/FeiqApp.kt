package com.feiq.droid.core

import android.app.Application

/**
 * App 启动入口：在任何 Activity 创建前应用夜间模式偏好。
 */
class FeiqApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.applyNightMode(Prefs.nightMode(this))
    }
}
