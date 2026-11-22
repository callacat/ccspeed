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
 * 改走 jniLibs/<abi>/ccspeed：APK installer 把它解压到 nativeLibraryDir
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

    private fun binaryFile(ctx: Context): File = File(ctx.filesDir, "ccspeed-bin").apply { mkdirs() }.let {
        File(it, "ccspeed")
    }

    /**
     * 从 assets 读取 ccspeed ELF → 写入 filesDir/ccspeed-bin/ccspeed → setExecutable → exec。
     * Android 10+ SELinux 允许 untrusted_app 在自有 filesDir 上 exec（Termux/v2rayNG 同款姿势）。
     * 不走 nativeLibraryDir：AGP 8.x 不把自定义命名的 libccspeed.so 当 native library 抽取；
     * assets 方案是 Termux/v2rayNG 标准姿势，SELinux 允许 app_data_file exec。
     */
    private fun nativeSource(ctx: Context): File? {
        val abis = Build.SUPPORTED_ABIS ?: emptyArray()
        val assetName = when {
            abis.contains("arm64-v8a") -> "ccspeed-linux-arm64"
            abis.contains("x86_64") -> "ccspeed-linux-x86_64"
            else -> {
                Log.w(TAG, "no known ABI in ${abis.toList()}, fallback arm64")
                "ccspeed-linux-arm64"
            }
        }
        val dst = binaryFile(ctx)
        try {
            ctx.assets.open(assetName).use { input ->
                dst.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
            }
            dst.setExecutable(true, false)
            if (dst.canExecute()) {
                Log.i(TAG, "native source copied from assets/$assetName executable: ${dst.absolutePath}")
                return dst
            }
        } catch (e: Throwable) {
            Log.e(TAG, "copy from assets failed", e)
        }
        Log.e(TAG, "native source copy failed: asset=$assetName dst=${dst.absolutePath} canExec=${dst.canExecute()}")
        return null
    }

    /** 幂等：若 nativeSource 可直接 exec 就直接返回，不再 copy。 */
    @Synchronized
    fun ensureBinary(ctx: Context): File? {
        val src = nativeSource(ctx) ?: return null
        Log.i(TAG, "binary ready path=${src.absolutePath}")
        return src
    }

    @Synchronized
    fun start(ctx: Context): Boolean {
        Log.i(TAG, "start:1 alive check")
        if (process?.alive() == true) return true
        Log.i(TAG, "start:2 killStale")
        killStale()
        Log.i(TAG, "start:3 ensureBinary")
        val bin = ensureBinary(ctx)
        Log.i(TAG, "start:4 ensureBinary done bin=${bin?.absolutePath}")
        if (bin == null) {
            lastLog = "no native source (nativeLibraryDir 缺 ccspeed ELF 或无法 setExecutable)"
            Log.e(TAG, lastLog)
            return false
        }
        Log.i(TAG, "start:5 abi/log binary=${bin.absolutePath} canExec=${bin.canExecute()}")
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

    /** pkill 两套资产名，防 ABI 切换后残留进程占端口。用 pgrep 找 PID → 过滤自身 PID → 再 kill，防误杀 FGS 进程。 */
    private fun killStale() {
        val myPid = android.os.Process.myPid()
        for (name in listOf("ccspeed", "ccspeed-linux-arm64", "ccspeed-linux-amd64")) {
            runCatching {
                // pgrep 找所有匹配 name 的 PID，过滤掉自身 PID，再逐个 kill
                val cmd = "pgrep -f $name | grep -v ^$myPid$ | xargs -r kill"
                ProcessBuilder("sh", "-c", cmd)
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