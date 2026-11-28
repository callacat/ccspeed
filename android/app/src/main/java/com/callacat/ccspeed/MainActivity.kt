package com.callacat.ccspeed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 主界面：连接/断开 + 节点状态 + 控制台入口。
 * 业务 UI 仍在 Web 控制台，这里只展示运行状态并提供入口。
 */
class MainActivity : AppCompatActivity() {
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var toggleButton: MaterialButton
    private lateinit var crashText: TextView

    private var wantVpn = false
    private var statusJob: Job? = null

    private val vpnConflictReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CcspeedVpnService.ACTION_VPN_CONFLICT) {
                runOnUiThread {
                    wantVpn = false
                    renderToggle()
                    statusTitle.text = getString(R.string.main_status_disconnected)
                    statusDetail.text = getString(R.string.vpn_conflict)
                    Toast.makeText(this@MainActivity, R.string.vpn_conflict, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            try {
                CcspeedRuntime.crashLogFile.writeText(throwable.stackTrace.joinToString("\n") { it.toString() })
            } catch (_: Throwable) {}
        }
        setContentView(R.layout.activity_main)

        statusTitle = findViewById(R.id.statusTitle)
        statusDetail = findViewById(R.id.statusDetail)
        toggleButton = findViewById(R.id.toggleButton)
        crashText = findViewById(R.id.crashText)
        findViewById<TextView>(R.id.versionText).text =
            getString(R.string.main_version, BuildConfig.VERSION_NAME)

        toggleButton.setOnClickListener {
            if (wantVpn || CcspeedRuntime.runningFlow.value) onStopClicked() else onStartClicked()
        }
        findViewById<MaterialButton>(R.id.consoleButton).setOnClickListener {
            startActivity(Intent(this, ConsoleActivity::class.java))
        }

        showCrashLogIfAny()
        registerReceiver(
            vpnConflictReceiver,
            IntentFilter(CcspeedVpnService.ACTION_VPN_CONFLICT),
            Context.RECEIVER_NOT_EXPORTED,
        )

        lifecycleScope.launch {
            CcspeedRuntime.runningFlow.collect { renderRunning(it) }
        }
    }

    private fun showCrashLogIfAny() {
        try {
            val crashFile = CcspeedRuntime.crashLogFile
            if (!crashFile.exists()) return
            val preview = crashFile.readText().trim().split("\n").take(2).joinToString(" | ")
            if (preview.isNotEmpty()) {
                crashText.text = getString(R.string.main_crash, preview)
                crashText.visibility = TextView.VISIBLE
                Log.w(TAG, "crash log found: $preview")
            }
        } catch (_: Throwable) {}
    }

    /** 按钮跟随「意图或实际运行」：授权弹窗/启动失败时也能点断开收尾。 */
    private fun renderToggle() {
        val active = wantVpn || CcspeedRuntime.runningFlow.value
        toggleButton.text =
            getString(if (active) R.string.main_btn_disconnect else R.string.main_btn_connect)
        val bgAttr =
            if (active) com.google.android.material.R.attr.colorError
            else com.google.android.material.R.attr.colorPrimary
        val fgAttr =
            if (active) com.google.android.material.R.attr.colorOnError
            else com.google.android.material.R.attr.colorOnPrimary
        toggleButton.backgroundTintList =
            ColorStateList.valueOf(MaterialColors.getColor(toggleButton, bgAttr))
        toggleButton.setTextColor(MaterialColors.getColor(toggleButton, fgAttr))
    }

    private fun renderRunning(running: Boolean) {
        renderToggle()
        statusTitle.text =
            getString(if (running) R.string.main_status_connected else R.string.main_status_disconnected)
        statusTitle.setTextColor(
            MaterialColors.getColor(
                statusTitle,
                if (running) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOnSurface,
            ),
        )

        if (!running) {
            statusDetail.text = getString(R.string.main_status_idle_hint)
            statusJob?.cancel()
            statusJob = null
            return
        }
        statusDetail.text = getString(R.string.main_status_connecting)
        startStatusPolling()
    }

    /** 轮询本机 Web 控制台 /api/status，展示当前节点、地区与延迟。 */
    private fun startStatusPolling() {
        if (statusJob?.isActive == true) return
        statusJob = lifecycleScope.launch {
            while (isActive) {
                val info = withContext(Dispatchers.IO) { runCatching { fetchNodeStatus() }.getOrNull() }
                if (!CcspeedRuntime.runningFlow.value) break
                statusDetail.text = when {
                    info == null -> getString(R.string.main_status_no_info)
                    info.name.isBlank() -> getString(R.string.main_status_picking)
                    else -> getString(
                        R.string.main_status_detail,
                        info.name,
                        info.group,
                        info.latencyMs,
                    )
                }
                delay(5000)
            }
        }
    }

    private fun fetchNodeStatus(): NodeStatus? {
        val conn = (URL("http://127.0.0.1:28981/api/status").openConnection() as HttpURLConnection).apply {
            connectTimeout = 800
            readTimeout = 1500
        }
        return try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            NodeStatus(
                name = json.optString("node_name"),
                group = json.optString("node_group"),
                latencyMs = json.optInt("latency_ms"),
            )
        } finally {
            conn.disconnect()
        }
    }

    private data class NodeStatus(val name: String, val group: String, val latencyMs: Int)

    override fun onDestroy() {
        super.onDestroy()
        statusJob?.cancel()
        try { unregisterReceiver(vpnConflictReceiver) } catch (_: Throwable) {}
    }

    private fun onStartClicked() {
        val intent: Intent? = VpnService.prepare(this)
        wantVpn = true
        renderToggle()
        if (intent != null) {
            startActivityForResult(intent, REQ_VPN)
        } else {
            startVpn()
        }
    }

    private fun onStopClicked() {
        wantVpn = false
        CcspeedVpnService.stop(this)
        CcspeedRuntime.stop()
        renderToggle()
        Log.i(TAG, "user stopped")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                wantVpn = true
                startVpn()
            } else {
                wantVpn = false
                renderToggle()
                statusDetail.text = getString(R.string.vpn_auth_denied)
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun startVpn() {
        CcspeedVpnService.start(this)
        // 放在 VPN 拉起之后：先走完 VpnService 授权弹窗，再引导电池白名单，避免两层对话框叠加。
        if (!isIgnoringBatteryOptimizations()) {
            Log.i(TAG, "not whitelisted, prompting battery optimization whitelist")
            requestIgnoreBatteryOptimizations()
        }
    }

    /** ColorOS 等 ROM 会清理后台 service 导致 VPN 中断，引导用户加白。 */
    private fun isIgnoringBatteryOptimizations(): Boolean = runCatching {
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }.getOrDefault(true)

    private fun requestIgnoreBatteryOptimizations() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }.onFailure { Log.w(TAG, "battery exemption request failed: $it") }
    }

    companion object {
        private const val TAG = "ccspeed-main"
        private const val REQ_VPN = 1001
    }
}