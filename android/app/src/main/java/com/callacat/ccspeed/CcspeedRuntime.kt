package com.callacat.ccspeed

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * ccspeed native 进程管理。二进制不走 assets → filesDir 解压 exec：Android 10+
 * SELinux 禁止 untrusted_app 执行 app_data_file 上的可执行文件（run 44984072424
 * 见 `avc: denied { execute_no_trans } path=/data/data/.../ccspeed-bin/
 * ccspeed-linux-arm64`，exec 抛 error=13 Permission denied）。
 *
 * 改走 jniLibs/<abi>/libccspeed.so：APK installer 把它解压到 nativeLibraryDir
 * （/data/app/.../lib/<abi>/），该目录是 SELinux 允许 untrusted_app exec 的
 * native_library 区，v2rayNG / Termux 同款姿势。壳只做按 ABI 挑目录 + exec。
 */
object CcspeedRuntime {
    private const val TAG = "ccspeed-runtime"

    const val LISTEN = "127.0.0.1:1081"
    const val WEB = "127.0.0.1:28981"
    const val WEB_URL = "http://$WEB/"

    @Volatile
    private var process: Process? = null

    @Volatile
    var lastLog: String = ""
        private set

    /** 运行时保存的 Application Context（start 时注入），供 filesDir/crashLogFile 使用 */
    @Volatile
    private var appContext: Context? = null

    /** 崩溃日志文件路径 */
    val crashLogFile: File
        get() = File(filesDir(), "crash.log")

    /** 应用内部文件目录（context.filesDir） */
    private fun filesDir(): File = appContext?.filesDir ?: throw IllegalStateException("appContext not initialized, call start() first")

    /**
     * 根据当前 ABI 返回 nativeLibraryDir 下的 libccspeed.so 路径。
     * nativeLibraryDir 示例：/data/app/~~xxx==/com.callacat.ccspeed-zzz==/lib/arm64/
     * 里面直接有 libccspeed.so（由 installer 从 jniLibs/<abi>/libccspeed.so 抽取而来）。
     */
    private fun nativeBinary(ctx: Context): File {
        val abi = when {
            Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "arm64"
            Build.SUPPORTED_ABIS.contains("x86_64") -> "x86_64"
            else -> {
                Log.w(TAG, "unknown ABI ${Build.SUPPORTED_ABIS.toList()}, fallback arm64")
                "arm64"
            }
        }
        // nativeLibraryDir 已经是对应 ABI 的目录，直接拼文件名
        return File(ctx.applicationInfo.nativeLibraryDir, "libccspeed.so")
    }

    /** 校验二进制是否存在且可执行 */
    private fun verifyBinary(bin: File): Boolean {
        if (!bin.exists()) {
            Log.e(TAG, "native binary not found: ${bin.absolutePath}")
            return false
        }
        if (!bin.canExecute()) {
            Log.e(TAG, "native binary not executable: ${bin.absolutePath}")
            return false
        }
        Log.i(TAG, "native binary verified: ${bin.absolutePath}")
        return true
    }

    @Synchronized
    fun start(ctx: Context): Boolean {
        if (process?.alive() == true) return true
        // 保存 application context 供 filesDir/crashLogFile 使用
        appContext = ctx.applicationContext
        // 清残留：上次强杀后可能仍有同名进程占端口。两套资产名都 pkill，
        // 因为 ABI 切换（真机→AVD）时上一套名字的进程可能残留。
        killStale()
        val bin = nativeBinary(ctx)
        if (!verifyBinary(bin)) return false
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
        killStale()
        lastLog = "stopped"
        Log.i(TAG, lastLog)
    }

    val running: Boolean
        get() = process?.alive() == true

    /** pkill 两套资产名，防 ABI 切换后残留进程占端口。 */
    private fun killStale() {
        for (name in listOf("libccspeed.so", "ccspeed-linux-arm64", "ccspeed-linux-amd64")) {
            runCatching {
                ProcessBuilder("sh", "-c", "pkill -f $name")
                    .redirectErrorStream(true).start().waitFor()
            }
        }
    }

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
