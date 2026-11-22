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

    private fun binaryFile(ctx: Context): File = File(ctx.filesDir, "ccspeed-bin/libccspeed.so").also { it.parentFile?.mkdirs() }

    /**
     * 直接从 nativeLibraryDir 取 ccspeed ELF：APK installer 解压 jniLibs/
     * 到 /data/app/.../lib/<abi>/libccspeed.so，该目录 SELinux 标签允许
     * untrusted_app exec（run 44984072424 staged 到 dataDir/ccspeed-bin 报
     * execute_no_trans denied，SELinux 拦 dataDir 放行 nativeLibraryDir）。
     * 命名必须 lib*.so 否则 installer 不抽（v2rayNG/Xray 同款姿势）。
     *
     * 关键：installer 解压 .so 时默认 644 无执行位，必须显式 setExecutable。
     * 若 nativeLibraryDir 只读无法加位，则回退 copy 到 filesDir/ccspeed-bin/ 再 exec。
     */
    private fun nativeSource(ctx: Context): File? {
        val libDir = ctx.applicationInfo.nativeLibraryDir ?: return null
        val src = File(libDir, "libccspeed.so")
        if (!src.exists()) {
            Log.w(TAG, "native source not found: ${src.absolutePath}")
            return null
        }
        // 尝试加执行位（nativeLibraryDir 通常只读，setExecutable 可能失败/静默忽略）
        if (!src.canExecute()) {
            try { src.setExecutable(true, false) } catch (_: Throwable) { }
        }
        if (src.canExecute()) {
            Log.i(TAG, "native source executable at nativeLibraryDir: ${src.absolutePath}")
            return src
        }
        // 回退：copy 到 filesDir/ccspeed-bin/ 再 exec（Termux/v2rayNG 同款姿势）
        val dst = binaryFile(ctx)
        try {
            src.inputStream().use { input ->
                dst.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
            }
            dst.setExecutable(true, false)
            if (dst.canExecute()) {
                Log.i(TAG, "native source copied to filesDir executable: ${dst.absolutePath}")
                return dst
            }
        } catch (e: Throwable) {
            Log.e(TAG, "copy to filesDir failed", e)
        }
        Log.e(TAG, "native source not executable anywhere: src=${src.absolutePath} canExec=${src.canExecute()} dst=${dst.absolutePath} canExec=${dst.canExecute()}")
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

    /** pkill 两套资产名，防 ABI 切换后残留进程占端口。 */
    private fun killStale() {
        for (name in listOf("ccspeed", "ccspeed-linux-arm64", "ccspeed-linux-amd64")) {
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