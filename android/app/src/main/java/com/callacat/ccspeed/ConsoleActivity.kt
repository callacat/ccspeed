package com.callacat.ccspeed

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout

/**
 * 内置 WebView 控制台页：点「打开控制台」直接在应用内打开（不跳出 app，体验连续）。
 * 页面顶部提供「在浏览器打开」按钮，保留外部浏览器入口。
 */
class ConsoleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val openInBrowser = Button(this).apply {
            text = "在浏览器打开"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(16, 16, 16, 8) }
            setOnClickListener {
                val uri = Uri.parse(CcspeedRuntime.WEB_URL)
                val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            }
        }

        val web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { weight = 1f }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webViewClient = WebViewClient()
            loadUrl(CcspeedRuntime.WEB_URL)
        }

        root.addView(openInBrowser)
        root.addView(web)
        setContentView(root)
    }
}