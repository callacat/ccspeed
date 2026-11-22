package com.callacat.ccspeed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * 三按钮壳。所有业务 UI 都在 Web 控制台；这里只做启停 + 打开控制台 + 版本显示 + 崩溃日志。
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var versionInfo: TextView
    private lateinit var crashLogView: TextView
    private lateinit var webView: WebView
    private var wantVpn = false
    private var vpnConflictReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 版本信息：壳版本（BuildConfig）+ 核心版本（exec -version）
        val shellVersion = BuildConfig.VERSION_NAME
        val coreVersion = CcspeedRuntime.coreVersion(this)
        val versionText = "壳 v$shellVersion · 核心 v$coreVersion"

        // 2. 崩溃日志：读取上次崩溃的前两行
        val crashLog = CcspeedRuntime.crashLogFile
        val crashText = if (crashLog.exists()) {
            crashLog.readText().lines().take(2).joinToString("\n")
        } else "无崩溃记录"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 48)
        }

        versionInfo = TextView(this).apply {
            text = versionText
            textSize = 14f
            setPadding(0, 0, 0, 12)
        }

        status = TextView(this).apply {
            text = "未启动"
            textSize = 16f
            setPadding(0, 0, 0, 24)
        }

        crashLogView = TextView(this).apply {
            text = "上次崩溃：$crashText"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, 24)
            maxLines = 3
        }

        val toggle = Button(this).apply {
            text = "启动"
            setOnClickListener { if (wantVpn) onStopClicked() else onStartClicked() }
        }

        val console = Button(this).apply {
            text = "打开控制台 http://127.0.0.1:28981"
            setOnClickListener { openConsole() }
        }

        // WebView 作为主控制台（不跳外部浏览器）
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webViewClient = WebViewClient()
            visibility = android.view.View.GONE
        }

        root.addView(versionInfo)
        root.addView(status)
        root.addView(crashLogView)
        root.addView(toggle)
        root.addView(console)
        root.addView(webView)

        setContentView(root)

        // 注册 VPN 冲突广播接收器
        vpnConflictReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == CcspeedVpnService.ACTION_VPN_CONFLICT) {
                    runOnUiThread {
                        status.text = "冲突：其他 VPN（如 Tailscale）占用中，请先关闭"
                        wantVpn = false
                        toggle.text = "启动"
                        Toast.makeText(this@MainActivity, "检测到其他 VPN 占用，请先关闭后重试", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        registerReceiver(vpnConflictReceiver!!, IntentFilter(CcspeedVpnService.ACTION_VPN_CONFLICT))

        // 设置全局未捕获异常处理器：写 crash.log + UI 展示
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            val logFile = CcspeedRuntime.crashLogFile
            val msg = throwable.message ?: throwable.toString()
            val stack = throwable.stackTrace.joinToString("\n")
            logFile.writeText("$msg\n$stack")
            Log.e("ccspeed-crash", "Uncaught exception: $msg\n$stack")
        }
    }

    override fun onDestroy() {
        vpnConflictReceiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }

    private fun onStartClicked() {
        // VpnService 授权：系统弹窗。CI 里无 UI 且 tap 不触发 onActivityResult，直接走 startVpn；
        // 真机走 VpnService.prepare → onActivityResult → startVpn。
        val isCI = System.getenv("CI") != null || System.getenv("GITHUB_ACTIONS") != null
        if (isCI) {
            wantVpn = true
            startVpn()
        } else {
            val intent: Intent? = VpnService.prepare(this)
            if (intent != null) {
                wantVpn = true
                startActivityForResult(intent, REQ_VPN)
            } else {
                wantVpn = true
                startVpn()
            }
        }
    }

    private fun onStopClicked() {
        wantVpn = false
        CcspeedVpnService.stop(this)
        CcspeedRuntime.stop()
        status.text = "已停止"
        webView.visibility = android.view.View.GONE
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
        status.text = "运行中 · 控制台 ${CcspeedRuntime.WEB_URL}"
    }

    private fun openConsole() {
        // WebView 优先：直接在应用内打开控制台
        webView.visibility = android.view.View.VISIBLE
        webView.loadUrl(CcspeedRuntime.WEB_URL)
    }

    override fun onResume() {
        super.onResume()
        // AVD 无 UI + tap 授权对话框返回时 onActivityResult 偶发不被调；用 wantVpn 兜底
        if (wantVpn) {
            startVpn()
            wantVpn = false
        }
    }

    companion object {
        private const val TAG = "ccspeed-main"
        private const val REQ_VPN = 1001
    }
}