package com.callacat.ccspeed

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/**
 * 分应用代理选择页（独立 Activity，后续 UI 改版可直接沿用）。
 * ListView + CheckBox 全部用系统控件，零新依赖。
 */
class PerAppProxyActivity : AppCompatActivity() {
    private data class AppEntry(val pkg: String, val label: String)

    private lateinit var list: ListView
    private lateinit var hint: TextView
    private var entries: List<AppEntry> = emptyList()
    /** 初次载入时抑制回调，避免把 UI 初始化当成用户改动去重启 VPN */
    private var loading = true
    /** 连续勾选合并成一次重启，避免每点一下都断流 */
    private val handler = Handler(Looper.getMainLooper())
    private val restartTask = Runnable { restartVpnIfRunning() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 24)
        }
        val switch = SwitchCompat(this).apply {
            text = "仅选中应用走 VPN"
            isChecked = PerAppConfig.mode(this@PerAppProxyActivity) == PerAppConfig.MODE_WHITELIST
            setOnCheckedChangeListener { _, checked ->
                if (loading) return@setOnCheckedChangeListener
                PerAppConfig.setMode(
                    this@PerAppProxyActivity,
                    if (checked) PerAppConfig.MODE_WHITELIST else PerAppConfig.MODE_FULL,
                )
                updateHint()
                scheduleRestart()
            }
        }
        hint = TextView(this).apply {
            textSize = 14f
            setPadding(0, 8, 0, 16)
        }
        list = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
        }
        root.addView(switch)
        root.addView(hint)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        entries = loadLaunchableApps()
        list.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_multiple_choice,
            entries.map { it.label },
        )
        val selected = PerAppConfig.selected(this)
        entries.forEachIndexed { i, e -> list.setItemChecked(i, e.pkg in selected) }
        list.setOnItemClickListener { _, _, position, _ ->
            val current = PerAppConfig.selected(this).toMutableSet()
            if (list.isItemChecked(position)) current.add(entries[position].pkg)
            else current.remove(entries[position].pkg)
            PerAppConfig.setSelected(this, current)
            updateHint()
            scheduleRestart()
        }
        loading = false
        updateHint()
    }

    override fun onDestroy() {
        // 只取消待触发的「合并重启」；已排队的 VPN 重建要放行，否则关页后 VPN 停在停止态
        handler.removeCallbacks(restartTask)
        super.onDestroy()
    }

    /** 只列可启动应用：ACTION_MAIN + CATEGORY_LAUNCHER，按包名去重、按名称排序。 */
    private fun loadLaunchableApps(): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching { packageManager.queryIntentActivities(intent, 0) }
            .getOrDefault(emptyList())
        val seen = HashSet<String>()
        return resolved.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == packageName || !seen.add(pkg)) return@mapNotNull null
            val label = runCatching { ri.loadLabel(packageManager).toString() }.getOrDefault(pkg)
            AppEntry(pkg, label)
        }.sortedBy { it.label.lowercase() }
    }

    private fun updateHint() {
        hint.text = PerAppConfig.summary(this)
    }

    private fun scheduleRestart() {
        handler.removeCallbacks(restartTask)
        handler.postDelayed(restartTask, 400)
    }

    /** 改选择时 VPN 正在跑 → 先 teardown 再重建，让新路由立即生效。 */
    private fun restartVpnIfRunning() {
        if (!CcspeedVpnService.isRunning) return
        CcspeedVpnService.stop(this)
        CcspeedRuntime.stop()
        // stopService → onDestroy 是异步的，留出 tun 释放的时间再重建
        handler.postDelayed({ CcspeedVpnService.start(this) }, 600)
        Toast.makeText(this, "已重启 VPN 生效", Toast.LENGTH_SHORT).show()
    }
}