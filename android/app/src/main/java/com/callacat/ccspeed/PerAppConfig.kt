package com.callacat.ccspeed

import android.content.Context

/**
 * 分应用代理配置（SharedPreferences `perapp`）。
 * mode=full 表示全量（兼容现状）；mode=whitelist 且有效选中集非空时，只有选中应用走 VPN。
 */
object PerAppConfig {
    const val PREFS = "perapp"
    const val KEY_MODE = "mode"
    const val KEY_PACKAGES = "packages"
    const val MODE_FULL = "full"
    const val MODE_WHITELIST = "whitelist"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(ctx: Context): String =
        prefs(ctx).getString(KEY_MODE, MODE_FULL) ?: MODE_FULL

    fun setMode(ctx: Context, mode: String) {
        prefs(ctx).edit().putString(KEY_MODE, mode).apply()
    }

    fun selected(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_PACKAGES, emptySet())?.toSet() ?: emptySet()

    fun setSelected(ctx: Context, packages: Set<String>) {
        prefs(ctx).edit().putStringSet(KEY_PACKAGES, packages.toSet()).apply()
    }

    /** 有效选中集 = 选中包名 ∩ 当前已安装（被卸载的包不能交给 addAllowedApplication）。 */
    fun effectiveSelected(ctx: Context): Set<String> {
        val sel = selected(ctx)
        if (sel.isEmpty()) return emptySet()
        val pm = ctx.packageManager
        return sel.filter { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
        }.toSet()
    }

    /** 白名单是否真实生效：开关打开且有效选中集非空；否则按全量处理。 */
    fun whitelistActive(ctx: Context): Boolean =
        mode(ctx) == MODE_WHITELIST && effectiveSelected(ctx).isNotEmpty()

    /** 首页/选择页摘要。白名单 0 个时必须明示等同全量，避免误以为在走白名单。 */
    fun summary(ctx: Context): String {
        if (mode(ctx) != MODE_WHITELIST) return "分应用：全量（所有应用走 VPN）"
        val n = effectiveSelected(ctx).size
        return if (n == 0) "分应用：仅选中（0 个，等同全量）" else "分应用：仅 $n 个应用走 VPN"
    }
}