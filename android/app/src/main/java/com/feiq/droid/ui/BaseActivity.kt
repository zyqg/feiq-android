package com.feiq.droid.ui

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity
import com.feiq.droid.core.Prefs

/**
 * 应用字体缩放偏好的基类 Activity。
 * 通过覆盖 Configuration.fontScale 让"小/标准/大"设置全局生效。
 */
abstract class BaseActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        val factor = Prefs.fontScaleFactor(newBase)
        if (factor != 1f) {
            val cfg = Configuration(newBase.resources.configuration)
            cfg.fontScale = cfg.fontScale * factor
            super.attachBaseContext(newBase.createConfigurationContext(cfg))
        } else {
            super.attachBaseContext(newBase)
        }
    }
}
