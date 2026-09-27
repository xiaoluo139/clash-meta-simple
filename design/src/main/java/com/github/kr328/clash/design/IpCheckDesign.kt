package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.github.kr328.clash.design.databinding.DesignIpCheckBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

/**
 * Built-in IP check page (https://ip.skk.moe/).
 *
 * Runs inside the app so the user can verify the exit IP and connectivity
 * without leaving the client.
 */
class IpCheckDesign(context: Context) : Design<IpCheckDesign.Request>(context) {
    sealed class Request {
        object Reload : Request()
    }

    private val binding = DesignIpCheckBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    val canGoBack: Boolean
        get() = binding.webView.canGoBack()

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.reloadView.setOnClickListener {
            requests.trySend(Request.Reload)
        }

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progressView.visibility = View.GONE
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressView.progress = newProgress
                binding.progressView.visibility =
                    if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }

        reload()
    }

    fun reload() {
        binding.progressView.visibility = View.VISIBLE
        binding.progressView.progress = 0

        binding.webView.loadUrl(URL)
    }

    fun goBack(): Boolean {
        if (!binding.webView.canGoBack())
            return false

        binding.webView.goBack()

        return true
    }

    companion object {
        const val URL = "https://ip.skk.moe/"
    }
}