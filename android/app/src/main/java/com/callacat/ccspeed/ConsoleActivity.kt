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
 * VPN 生效时，WebView 走 VPN 隧道，回环地址会被路由进隧道导致无法访问本地控制台。
 * 解法：VPN 生效时改用 VPN 接口 IP (10.200.0.2) 访问控制台，控制台监听 0.0.0.0:28981 覆盖该接口。
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
                val uri = Uri.parse("http://127.0.0.1:28981/")
                val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            }
        }

        // VPN 接口固定 IP，控制台监听 0.0.0.0:28981 覆盖该接口
        val consoleUrl = "http://10.200.0.2:28981/"

        val web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { weight = 1f }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError) {
                    android.util.Log.e("ConsoleActivity", "WebView error: ${error.description}")
                    // 备用：尝试模拟器宿主 IP
                    if (request?.url?.toString()?.startsWith("http://10.200.0.2:28981/") == true) {
                        view.loadUrl("http://10.0.2.2:28981/")
                    }
                }
                override fun onReceivedHttpError(view: WebView, request: android.webkit.WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                    android.util.Log.e("ConsoleActivity", "WebView HTTP error: ${errorResponse?.statusCode}")
                }
            }
            loadUrl(consoleUrl)
        }

        root.addView(openInBrowser)
        root.addView(web)
        setContentView(root)
    }
}