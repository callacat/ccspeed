package com.callacat.ccspeed

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.util.Log
import android.widget.Toast
import androidx.annotation.Nullable

/** 三按钮壳。所有业务 UI 都在 Web 控制台；这里只做启停 + 打开控制台。 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private var wantVpn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(36, 48, 36, 48)
        }
        status = TextView(this).apply {
            text = "未启动 v" + BuildConfig.VERSION_NAME
            textSize = 16f
            setPadding(0, 0, 0, 24)
        }
        val toggle = Button(this).apply {
            text = "启动"
            setOnClickListener { if (wantVpn) onStopClicked() else onStartClicked() }
        }
        val console = Button(this).apply {
            text = "打开控制台 http://127.0.0.1:28981"
            setOnClickListener { openConsole() }
        }
        root.addView(status)
        root.addView(toggle)
        root.addView(console)
        setContentView(root)
    }

    private fun onStartClicked() {
        val intent: Intent? = VpnService.prepare(this)
        if (intent != null) {
            wantVpn = true
            startActivityForResult(intent, REQ_VPN)
        } else {
            wantVpn = true
            startVpn()
        }
    }

    private fun onStopClicked() {
        wantVpn = false
        CcspeedVpnService.stop(this)
        CcspeedRuntime.stop()
        status.text = "已停止"
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
        val uri = Uri.parse(CcspeedRuntime.WEB_URL)
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
