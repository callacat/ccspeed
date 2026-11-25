package com.callacat.ccspeed

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.util.Log
import android.widget.Toast
import androidx.annotation.Nullable
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** 三按钮壳。所有业务 UI 都在 Web 控制台；这里只做启停 + 打开控制台。 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private var wantVpn = false
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
        root.addView(status)
        root.addView(toggle)
        root.addView(console)
        setContentView(root)

        // Observe runtime running state to keep button text in sync
        lifecycleScope.launch {
            CcspeedRuntime.runningFlow.collect { running ->
                toggle.text = if (running) "停止" else "启动"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(vpnConflictReceiver) } catch (_: Throwable) {}
    }

    private fun onStartClicked() {
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
        val uri = Uri.parse("http://127.0.0.1:28981/")
        val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
        } catch (e: android.content.ActivityNotFoundException) {
            // 真机无浏览器时兜底 WebView 一行页，避免「点了没反应」
            startActivity(
                Intent(this, ConsoleActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    companion object {
        private const val TAG = "ccspeed-main"
        private const val REQ_VPN = 1001
    }
}
