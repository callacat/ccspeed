package com.callacat.ccspeed

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/**
 * 内置 WebView 控制台：顶部返回栏避免困在网页里，右上角保留外部浏览器入口。
 * VPN 生效时回环可能被路由进隧道，按 回环 → VPN 接口 → 模拟器宿主 顺序兜底。
 */
class ConsoleActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private var urlIndex = 0

    private val candidates = listOf(
        "http://127.0.0.1:28981/",
        "http://10.200.0.2:28981/",
        "http://10.0.2.2:28981/",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_console)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.console_menu)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_browser) {
                openInBrowser(currentUrl())
                true
            } else {
                false
            }
        }

        web = findViewById(R.id.webView)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame != true) return
                android.util.Log.e("ConsoleActivity", "WebView error: ${error?.description}")
                loadNextCandidate()
            }
        }
        web.loadUrl(currentUrl())

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
    }

    private fun currentUrl(): String = candidates[urlIndex]

    private fun loadNextCandidate() {
        if (urlIndex + 1 >= candidates.size) return
        urlIndex += 1
        web.loadUrl(currentUrl())
    }

    private fun openInBrowser(url: String) {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}