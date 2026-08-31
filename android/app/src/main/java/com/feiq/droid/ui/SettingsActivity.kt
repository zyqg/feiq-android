package com.feiq.droid.ui

import android.os.Bundle
import android.view.View
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.feiq.droid.R
import com.feiq.droid.core.App
import com.feiq.droid.core.NetworkInfo
import com.feiq.droid.core.Prefs
import com.feiq.droid.databinding.ActivitySettingsBinding

class SettingsActivity : BaseActivity() {
    private lateinit var b: ActivitySettingsBinding

    private val nightLabels = arrayOf("跟随系统", "始终浅色", "始终深色")
    private val fontLabels = arrayOf("小", "标准", "大")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.rowNick.setOnClickListener { showNickEdit() }
        b.rowGroup.setOnClickListener { showGroupEdit() }
        b.rowNight.setOnClickListener { showNightMenu() }
        b.rowFont.setOnClickListener { showFontMenu() }
        b.rowPort.setOnClickListener { showPortEdit() }
        b.rowClearAll.setOnClickListener { confirmClearAll() }
        b.rowAbout.setOnClickListener { showAbout() }

        bindSwitch(b.swNotify, Prefs.notifyEnabled(this)) { Prefs.setNotifyEnabled(this, it) }
        bindSwitch(b.swVibrate, Prefs.notifyVibrate(this)) { Prefs.setNotifyVibrate(this, it) }
        bindSwitch(b.swSound, Prefs.notifySound(this)) { Prefs.setNotifySound(this, it) }
        bindSwitch(b.swAutoFile, Prefs.autoRecvFile(this)) { Prefs.setAutoRecvFile(this, it) }

        refreshValues()
    }

    private fun refreshValues() {
        val nick = Prefs.nick(this)
        val group = Prefs.group(this)
        val ip = NetworkInfo.localIp() ?: "未知 IP"
        b.settingsAvatar.text = UiUtil.initial(nick)
        b.settingsTitle.text = nick
        b.settingsSubtitle.text = "$group  ·  $ip  ·  端口 ${Prefs.port(this)}"
        b.valNick.text = nick
        b.valGroup.text = group
        b.valNight.text = nightLabels[Prefs.nightMode(this).coerceIn(0, nightLabels.lastIndex)]
        b.valFont.text = fontLabels[Prefs.fontScale(this).coerceIn(0, fontLabels.lastIndex)]
        b.valPort.text = Prefs.port(this).toString()
        b.valVersion.text = "v${versionName()}"
    }

    private fun bindSwitch(sw: Switch, init: Boolean, onChange: (Boolean) -> Unit) {
        sw.isChecked = init
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
        (sw.parent as? View)?.setOnClickListener { sw.toggle() }
    }

    private fun showNickEdit() {
        FeiqBottomSheet.input(
            this,
            title = "修改昵称",
            message = "电脑端飞秋会用这个名称显示你的手机。",
            hint = "昵称",
            value = Prefs.nick(this),
            confirmText = "保存",
        ) { raw ->
            val nick = raw.trim()
            if (nick.isBlank()) {
                toast("昵称不能为空")
                return@input
            }
            Prefs.setNick(this, nick)
            if (App.isStarted()) {
                App.engine().updateProfile(nick, Prefs.group(this))
                App.engine().refresh()
            }
            refreshValues()
            toast("昵称已更新")
        }
    }

    private fun showGroupEdit() {
        FeiqBottomSheet.input(
            this,
            title = "修改分组",
            message = "保存后会重新广播，Windows 飞秋会按这个分组归类。",
            hint = "例如：安卓组",
            value = Prefs.group(this),
            confirmText = "保存",
        ) { raw ->
            val group = raw.trim().ifBlank { "我的好友" }
            Prefs.setGroup(this, group)
            if (App.isStarted()) {
                App.engine().updateProfile(Prefs.nick(this), group)
                App.engine().refresh()
            }
            refreshValues()
            toast("分组已更新")
        }
    }

    private fun showNightMenu() {
        FeiqBottomSheet.menu(this, "夜间模式", null, nightLabels.mapIndexed { idx, label ->
            FeiqBottomSheet.Action(
                label = if (idx == Prefs.nightMode(this)) "$label  ·  当前" else label,
                iconRes = R.drawable.ic_settings,
            ) {
                Prefs.setNightMode(this, idx)
                refreshValues()
                recreate()
            }
        })
    }

    private fun showFontMenu() {
        FeiqBottomSheet.menu(this, "字体大小", null, fontLabels.mapIndexed { idx, label ->
            FeiqBottomSheet.Action(
                label = if (idx == Prefs.fontScale(this)) "$label  ·  当前" else label,
                iconRes = R.drawable.ic_edit,
            ) {
                Prefs.setFontScale(this, idx)
                refreshValues()
                recreate()
            }
        })
    }

    private fun showPortEdit() {
        val oldPort = Prefs.port(this)
        FeiqBottomSheet.input(
            this,
            title = "协议端口",
            message = "修改后会重启 UDP/TCP 监听并重新广播。",
            hint = "2425",
            value = oldPort.toString(),
            confirmText = "保存",
            inputType = android.text.InputType.TYPE_CLASS_NUMBER,
        ) { raw ->
            val port = raw.trim().toIntOrNull()
            if (port == null || port !in 1..65535) {
                toast("端口范围应为 1-65535")
                return@input
            }
            if (port == oldPort) return@input
            Prefs.setPort(this, port)
            if (App.isStarted()) App.engine().restartNetwork()
            refreshValues()
            toast("端口已更新并重新上线")
        }
    }

    private fun confirmClearAll() {
        FeiqBottomSheet.menu(this, "清空所有数据", "将删除聊天记录、缓存图片和本机设置。此操作不可恢复。", listOf(
            FeiqBottomSheet.Action("清空", R.drawable.ic_delete, danger = true) {
                Prefs.clearAll(this)
                toast("已清空，应用即将退出")
                b.root.postDelayed({ finishAffinity() }, 800)
            },
        ))
    }

    private fun showAbout() {
        FeiqBottomSheet.menu(
            this,
            "关于飞秋安卓版",
            "v${versionName()}\n兼容 Windows 飞秋 / IPMsg 基础协议，支持局域网发现、文字、表情、图片、文件和文件夹传输。\n当前协议端口：UDP/TCP ${Prefs.port(this)}",
            listOf(FeiqBottomSheet.Action("知道了", R.drawable.ic_badge) {})
        )
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "-"
    } catch (_: Exception) {
        "-"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
