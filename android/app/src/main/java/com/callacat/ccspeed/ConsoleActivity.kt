package com.callacat.ccspeed

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView

/**
 * 兜底控制台页：仅在真机无独立浏览器可拉起时使用。壳仍不复制任何业务 UI。
 */
class ConsoleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.loadUrl(CcspeedRuntime.WEB_URL)
    }
}