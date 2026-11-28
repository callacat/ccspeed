package com.callacat.ccspeed

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import hev.htproxy.TProxyService
import java.io.File

/**
 * 薄壳 VpnService：建 TUN → 写 hev yml → 交给 libhev-socks5-tunnel 转发到
 * ccspeed 的 SOCKS5。壳不复制任何业务 UI：凭据/节点/日志都在
 * http://127.0.0.1:28981 的 Web 控制台。
 */
class CcspeedVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand enter action=${intent?.action}")
        if (intent?.action == ACTION_STOP) {
            // 停止必须自包含：不能只 stopSelf() 依赖 onDestroy（实测 onDestroy 不保证执行 ->
            // tun0/hev 残留，系统仍认为 VPN 在跑，表现为"点停止无效、VPN 在但无网"）。
            stopForeground(STOP_FOREGROUND_REMOVE)
            teardown()
            CcspeedRuntime.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotify()
        Log.i(TAG, "startForegroundNotify done")
        // 顺序调整：先建 TUN/VPN（protectSocket 需 VPN 已建立），再起 ccspeed。
        // 这样 Web 控制台套接字可被 protectSocket 排除 VPN 路由。
        // SOCKS5 (1081) 短暂无监听，但 hev-socks5-tunnel 连接有重试，可接受。
        establishTunAndForward()
        Log.i(TAG, "after establishTunAndForward")
        Log.i(TAG, "before CcspeedRuntime.start")
        CcspeedRuntime.start(this)
        Log.i(TAG, "after CcspeedRuntime.start alive=${CcspeedRuntime.running}")
        return START_STICKY
    }

    override fun onRevoke() {
        stopSelf()
    }

    override fun onDestroy() {
        stopForeground(true)
        teardown()
        CcspeedRuntime.stop()
        super.onDestroy()
    }

    private fun establishTunAndForward() {
        if (tun != null) return
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .addAddress("10.200.0.2", 32)
            // 0.0.0.0/0 + ::/0 = 全流量进 TUN；DNS 走 hev 的 UDP-in-UDP，
            // ccspeed 的 udp.go 实测支持 UDP ASSOCIATE。
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("223.5.5.5")
            .setBlocking(false)
        // 分应用代理：白名单且有效选中集非空 → 只有选中应用进 VPN；否则全量（兼容现状）。
        val mode = PerAppConfig.mode(this)
        val allowed = if (PerAppConfig.whitelistActive(this)) PerAppConfig.effectiveSelected(this) else emptySet()
        Log.i(TAG, "route mode=$mode allowed=${allowed.size} [${allowed.joinToString(",")}]")
        if (mode == PerAppConfig.MODE_WHITELIST && allowed.isEmpty()) {
            Log.i(TAG, "whitelist selected but effective empty (0 apps) -> fallback to full")
        }
        allowed.forEach { pkg ->
            runCatching { builder.addAllowedApplication(pkg) }
                .onFailure { Log.w(TAG, "addAllowedApplication($pkg) failed: $it") }
        }
        if (allowed.isEmpty()) {
            // 全量模式：本 App（含同 UID 的 ccspeed 子进程）不走 VPN。否则 ccspeed dial 节点的
            // 出站被 tun0 捕获 -> hev -> 127.0.0.1:1081 回环（CMP-439）。
            // 白名单模式不能也调这个：Builder 禁止与 addAllowedApplication 混用（Android 14 实测抛
            // UnsupportedOperationException: addAllowedApplication already called）；而自身不在
            // allowed 列表里本就不进 VPN，天然满足直连条件。
            runCatching { builder.addDisallowedApplication(packageName) }
                .onFailure { Log.w(TAG, "addDisallowedApplication failed: $it") }
        }
        // 管理面保活：LAN + Tailscale(CGNAT) 不进 TUN，否则 adb/内网设备失联。
        // excludeRoute 需要 API 33+；更低版本接受全量（用户可临时关 VPN 管理）。
        if (Build.VERSION.SDK_INT >= 33) {
            listOf("192.168.0.0/16", "172.16.0.0/12", "100.64.0.0/10").forEach { cidr ->
                runCatching {
                    val (addr, len) = cidr.split("/")
                    builder.excludeRoute(
                        android.net.IpPrefix(java.net.InetAddress.getByName(addr), len.toInt()),
                    )
                }.onFailure { Log.w(TAG, "excludeRoute $cidr failed: $it") }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { builder.setMetered(false) }
        }
        val fd = runCatching { builder.establish() }.getOrNull()
        if (fd == null) {
            Log.e(TAG, "establish() 返回 null——授权被拒或接口冲突（如 Tailscale 等其他 VPN 占用）")
            // 通知 UI 显示冲突提示，而不是静默 stopSelf
            notifyVpnConflict()
            stopSelf()
            return
        }
        tun = fd
        isRunning = true
        Log.i(TAG, "tun up fd=${fd.fd}")

        val yml = writeHevConfig()
        val ok = runCatching { TProxyService.TProxyStartService(yml, fd.fd) }
            .onFailure { Log.e(TAG, "hev start failed: $it", it) }
            .getOrDefault(false)
        if (!ok) {
            Log.e(TAG, "hev TProxyStartService 返回 false")
            runCatching { fd.close() }
            tun = null
            stopSelf()
            return
        }
        Log.i(TAG, "hev started config=$yml")
    }

    /** 发送广播通知 MainActivity：VPN 接口冲突（其他 VPN 占用） */
    private fun notifyVpnConflict() {
        val intent = Intent(ACTION_VPN_CONFLICT)
        sendBroadcast(intent)
    }

    /**
     * hev yml：只要 socks5 指向 ccspeed，其余留默认。
     * mtu 与 VpnService.Builder 对齐 1500（hev 默认 8500 会上大包，
     * Android 侧要 IFF_VNET_HDR 才认，壳没开 offload → 会被 IP 层分片）。
     */
    private fun writeHevConfig(): String {
        val f = File(filesDir, "hev.yml").apply { parentFile?.mkdirs() }
        f.writeText(
            """
            tunnel:
              mtu: 1500
              multi-queue: false
              ipv4: 10.200.0.2
              ipv6: 'fc00::2'

            socks5:
              port: 1081
              address: 127.0.0.1
              udp: 'udp'
              pipeline: false

            misc:
              log-level: info
              log-file: ${File(filesDir, "hev.log").absolutePath}
              connect-timeout: 10000
              udp-read-write-timeout: 60000
            """.trimIndent(),
        )
        return f.absolutePath
    }

    private fun teardown() {
        runCatching { TProxyService.TProxyStopService() }
        runCatching { tun?.close() }
        tun = null
        isRunning = false
    }

    private fun startForegroundNotify() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.vpn_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CcspeedVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "停止",
                    stopIntent,
                ).build(),
            )
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        private const val TAG = "ccspeed-vpn"
        private const val CHANNEL_ID = "ccspeed_vpn"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.callacat.ccspeed.action.STOP"
        /** 广播 action：VPN 接口被其他应用（如 Tailscale）占用 */
        const val ACTION_VPN_CONFLICT = "com.callacat.ccspeed.action.VPN_CONFLICT"

        /** tun0 是否已建立；选择页据此判断要不要重启 VPN */
        @Volatile
        var isRunning = false
            private set

        fun start(context: android.content.Context) {
            val i = Intent(context, CcspeedVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: android.content.Context) {
            val i = Intent(context, CcspeedVpnService::class.java)
            // 双路停止：ACTION_STOP 让 service 自包含 teardown；stopService 兜底强制销毁。
            // 单独依赖任一路都实测不可靠（ACTION_STOP 可能被后台启动限制吞掉，
            // onDestroy 也可能不执行）。
            runCatching { context.startService(i.setAction(ACTION_STOP)) }
                .onFailure { Log.w(TAG, "stop via ACTION_STOP failed: $it") }
            runCatching { context.stopService(Intent(context, CcspeedVpnService::class.java)) }
                .onFailure { Log.w(TAG, "stopService failed: $it") }
        }
    }
}