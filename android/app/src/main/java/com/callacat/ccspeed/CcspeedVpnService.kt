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
import java.net.InetSocketAddress
import java.net.Socket

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
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotify()
        Log.i(TAG, "startForegroundNotify done")
        // 顺序很关键：先起 ccspeed（监听 1081/28981），再建 TUN 起 tun2socks。
        // 反过来 TUN 建好但 socks5 不在时，hev 的连接会全 fail 且不自愈。
        Log.i(TAG, "before CcspeedRuntime.start")
        CcspeedRuntime.start(this)
        Log.i(TAG, "after CcspeedRuntime.start alive=${CcspeedRuntime.running}")
        establishTunAndForward()
        Log.i(TAG, "after establishTunAndForward")
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
        Log.i(TAG, "tun up fd=${fd.fd}")

        // Protect ADB socket (port 5555) from VPN routing so ADB over TCP stays connected
        protectAdbSocket()

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
        adbSocket?.close()
        adbSocket = null
    }

    /** Protect ADB socket (port 5555) from VPN routing so ADB over TCP stays connected */
    private fun protectAdbSocket() {
        if (adbSocket != null) return
        runCatching {
            val socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", 5555), 2000)
            protect(socket)
            adbSocket = socket
            Log.i(TAG, "ADB socket protected")
        }.onFailure { Log.w(TAG, "Failed to protect ADB socket: $it") }
    }

    private var adbSocket: Socket? = null

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

        fun start(context: android.content.Context) {
            val i = Intent(context, CcspeedVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: android.content.Context) {
            val i = Intent(context, CcspeedVpnService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(i) }
                .onFailure { context.stopService(Intent(context, CcspeedVpnService::class.java)) }
        }
    }
}