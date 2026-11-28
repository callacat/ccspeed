package com.callacat.ccspeed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.util.Log
import android.widget.Toast
import androidx.annotation.Nullable
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** 三按钮壳。所有业务 UI 都在 Web 控制台；这里只做启停 + 打开控制台。 */
class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var modeSummary: TextView
    private lateinit var batteryStatus: TextView
    private var wantVpn = false
    /** 用户点启动后先去加白，返回界面再继续启动 */
    private var pendingStartAfterWhitelist = false
    private lateinit var toggle: Button
    private val vpnConflictReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CcspeedVpnService.ACTION_VPN_CONFLICT) {
                runOnUiThread {
                    status.text = "VPN 冲突：其他 VPN（如 Tailscale）占用中，请关闭后重试"
                    wantVpn = false
                    Toast.makeText(this@MainActivity, "VPN 接口被其他应用占用，请关闭其他 VPN 后重试", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全局未捕获异常处理器：写 crash.log 供下次启动展示
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            try {
                val crashFile = CcspeedRuntime.crashLogFile
                crashFile.writeText(throwable.stackTrace.joinToString("\n") { it.toString() })
            } catch (_: Throwable) {}
        }
        registerReceiver(vpnConflictReceiver, IntentFilter(CcspeedVpnService.ACTION_VPN_CONFLICT), Context.RECEIVER_NOT_EXPORTED)
        // 读取上次崩溃日志（若有）显示在状态区，避免闪退无感知
        var initStatus = "未启动 " + BuildConfig.VERSION_NAME
        try {
            val crashFile = CcspeedRuntime.crashLogFile
            if (crashFile.exists()) {
                val lines = crashFile.readText().trim().split("\n")
                if (lines.isNotEmpty()) {
                    val preview = lines.take(2).joinToString(" | ")
                    initStatus = "上次崩溃: $preview"
                    Log.w(TAG, "crash log found: $preview")
                }
            }
        } catch (_: Throwable) {}
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(36, 48, 36, 48)
        }
        status = TextView(this).apply {
            text = initStatus
            textSize = 16f
            setPadding(0, 0, 0, 24)
        }
        val toggle = Button(this).apply {
            text = "启动"
            setOnClickListener { if (wantVpn) onStopClicked() else onStartClicked() }
        }
        this.toggle = toggle
        val console = Button(this).apply {
            text = "打开控制台 http://127.0.0.1:28981"
            setOnClickListener { openConsole() }
        }
        modeSummary = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        val perApp = Button(this).apply {
            text = "分应用代理"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, PerAppProxyActivity::class.java))
            }
        }
        batteryStatus = TextView(this).apply {
            textSize = 14f
            setPadding(0, 24, 0, 8)
        }
        val keepAlive = Button(this).apply {
            text = "后台保活设置"
            setOnClickListener { requestIgnoreBatteryOptimizations() }
        }
        val colorOsHint = TextView(this).apply {
            textSize = 13f
            setText(
                "ColorOS 额外设置：设置 → 应用管理 → 本应用 → 耗电管理/允许后台活动 打开；" +
                    "再在最近任务卡片下拉锁定本应用，防止清理后台时被带走。",
            )
        }
        root.addView(status)
        root.addView(modeSummary)
        root.addView(toggle)
        root.addView(perApp)
        root.addView(console)
        root.addView(keepAlive)
        root.addView(batteryStatus)
        root.addView(colorOsHint)
        setContentView(root)

        // Observe runtime running state to keep button text in sync
        lifecycleScope.launch {
            CcspeedRuntime.runningFlow.collect { running ->
                toggle.text = if (running) "停止" else "启动"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从分应用选择页返回时刷新摘要（全量 / 仅 N 个应用）
        modeSummary.text = PerAppConfig.summary(this)
        refreshBatteryStatus()
        if (pendingStartAfterWhitelist) {
            pendingStartAfterWhitelist = false
            if (!isIgnoringBatteryOptimizations()) {
                Toast.makeText(this, "未加入电池优化白名单，锁屏后仍可能被系统清理", Toast.LENGTH_LONG).show()
            }
            beginStart()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(vpnConflictReceiver) } catch (_: Throwable) {}
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun refreshBatteryStatus() {
        batteryStatus.text = if (isIgnoringBatteryOptimizations()) {
            "后台保活：已加入电池优化白名单"
        } else {
            "后台保活：未加白，锁屏/清后台后可能被系统杀掉"
        }
    }

    /** 返回 true 表示已拉起系统页面；false 表示无法拉起，调用方自行继续。 */
    private fun requestIgnoreBatteryOptimizations(): Boolean {
        if (isIgnoringBatteryOptimizations()) {
            Toast.makeText(this, "已在电池优化白名单中", Toast.LENGTH_SHORT).show()
            return false
        }
        // 需要 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限；少数 ROM 会拒绝对话框，
        // 回退到电池优化列表页（无需权限），再不行就提示手动设置。
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        )
        return try {
            startActivity(direct)
            true
        } catch (e: Exception) {
            Log.w(TAG, "request ignore battery optimizations failed: $e")
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure {
                    Toast.makeText(this, "无法打开系统设置，请手动允许本应用后台运行", Toast.LENGTH_LONG).show()
                }
                .isSuccess
        }
    }

    private fun onStartClicked() {
        if (!isIgnoringBatteryOptimizations()) {
            // 先引导加白；拉起成功就等 onResume 回来再启动，不把加白当硬门槛
            pendingStartAfterWhitelist = true
            if (!requestIgnoreBatteryOptimizations()) {
                pendingStartAfterWhitelist = false
                beginStart()
            }
            return
        }
        beginStart()
    }

    private fun beginStart() {
        val intent: Intent? = VpnService.prepare(this)
        if (intent != null) {
            wantVpn = true
            startActivityForResult(intent, REQ_VPN)
        } else {
            wantVpn = true
            startVpn()
            toggle.text = "停止"
        }
    }

    private fun onStopClicked() {
        wantVpn = false
        CcspeedVpnService.stop(this)
        CcspeedRuntime.stop()
        status.text = "已停止"
        toggle.text = "启动"
        Log.i(TAG, "user stopped")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                wantVpn = true
                startVpn()
            } else {
                wantVpn = false
                status.text = "VpnService 授权被拒"
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun startVpn() {
        CcspeedVpnService.start(this)
        val shellVer = BuildConfig.VERSION_NAME
        val coreVer = CcspeedRuntime.coreVersion(this)
        // 去重：BuildConfig.VERSION_NAME 已含 v 前缀，coreVersion 也带 v，取其一即可
        val displayShell = if (shellVer.startsWith("v")) shellVer else "v$shellVer"
        val displayCore = if (coreVer.startsWith("v")) coreVer else "v$coreVer"
        // 显示用 127.0.0.1（浏览器访问），实际绑定 0.0.0.0（VPN 路由下仍可达）
        status.text = "壳 $displayShell · 核心 $displayCore · 控制台 http://127.0.0.1:28981/"
    }

    private fun openConsole() {
        // 始终使用内置 WebView，避免外部浏览器跨网络命名空间访问不了
        startActivity(
            Intent(this, ConsoleActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    companion object {
        private const val TAG = "ccspeed-main"
        private const val REQ_VPN = 1001
    }
}
