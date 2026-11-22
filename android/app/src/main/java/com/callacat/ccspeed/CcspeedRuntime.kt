package com.callacat.ccspeed

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * ccspeed native 进程管理：把 assets 里的 ccspeed-linux-arm64 解压到
 * app-internal 目录、chmod +x、拉起/杀掉。凭据/节点全部由 Web 控制台
 * 写 ccspeed.json，壳不看不存。
 */
object CcspeedRuntime {
    private const val TAG = "ccspeed-runtime"
    const val ASSET_BIN = "ccspeed-linux-arm64"
    const val LISTEN = "127.0.0.1:1081"
    const val WEB = "127.0.0.1:28981"
    const val WEB_URL = "http://$WEB/"

    @Volatile
    private var process: Process? = null

    @Volatile
    var lastLog: String = ""
        private set

    private fun binaryFile(ctx: Context): File = File(ctx.applicationInfo.dataDir, "ccspeed-bin").apply { mkdirs() }.let {
        File(it, ASSET_BIN)
    }

    /** 幂等解压：assets 的 sha256 与已落盘不同才覆盖（升级装机时使用）。 */
    @Synchronized
    fun ensureBinary(ctx: Context): File {
        val out = binaryFile(ctx)
        val tmp = File(out.parentFile, "$ASSET_BIN.tmp")
        ctx.assets.open(ASSET_BIN).use { input ->
            FileOutputStream(tmp).use { output -> input.copyTo(output, 256 * 1024) }
        }
        if (!out.exists() || tmp.length() != out.length()) {
            tmp.renameTo(out)
        } else {
            tmp.delete()
        }
        if (!out.canExecute()) {
            out.setExecutable(true, true)
        }
        Log.i(TAG, "binary ready path=${out.absolutePath} size=${out.length()}")
        return out
    }

    @Synchronized
    fun start(ctx: Context): Boolean {
        if (process?.alive() == true) return true
        // 清残留：上次强杀后可能仍有同名进程占端口（pkill 幂等）
        runCatching {
            ProcessBuilder("sh", "-c", "pkill -f $ASSET_BIN").redirectErrorStream(true).start().waitFor()
        }
        val bin = ensureBinary(ctx)
        val pb = ProcessBuilder(
            bin.absolutePath,
            "-listen", LISTEN,
            "-web", WEB,
        ).apply {
            // 与壳同 dataDir，ccspeed.json 由 Web 控制台写到这里
            directory(ctx.filesDir)
            redirectErrorStream(true)
            environment()["HOME"] = ctx.filesDir.absolutePath
        }.start()
        process = pb
        Thread({ drainLog(pb) }, "ccspeed-log").apply { isDaemon = true }.start()
        lastLog = "started alive=${pb.isAlive}"
        Log.i(TAG, lastLog)
        return true
    }

    @Synchronized
    fun stop() {
        val p = process
        process = null
        runCatching { p?.destroy() }
        // App 不退出时 destroy 足够；双保险防僵尸进程占端口
        runCatching {
            ProcessBuilder("sh", "-c", "pkill -f $ASSET_BIN").redirectErrorStream(true).start().waitFor()
        }
        lastLog = "stopped"
        Log.i(TAG, lastLog)
    }

    val running: Boolean
        get() = process?.alive() == true

    private fun Process.alive(): Boolean =
        try { isAlive } catch (_: Throwable) { false }

    /** stdout/stderr 合并进 ring：真机验收用 logcat 看不到壳进程的 stdout。 */
    private fun drainLog(p: Process) {
        try {
            p.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    lastLog = line
                    Log.i(TAG, line)
                }
            }
        } catch (_: Throwable) {
        }
    }
}