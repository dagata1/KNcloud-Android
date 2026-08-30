package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityRegisterWebBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.KNcloudAuthService
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class RegisterWebActivity : BaseActivity() {

    private val binding by lazy { ActivityRegisterWebBinding.inflate(layoutInflater) }
    private val isHandlingAuth = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        title = getString(R.string.login_btn_register)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        try {
            setupWebView()
            setupBackPress()

            val domain = MmkvManager.getApiDomain()
            val registerUrl = "$domain/#/register"
            binding.webView.loadUrl(registerUrl)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "WebView initialization error", e)
            val domain = MmkvManager.getApiDomain()
            val registerUrl = "$domain/#/register"
            com.v2ray.ang.util.Utils.openUri(this, registerUrl)
            finish()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        binding.webView.addJavascriptInterface(WebAppInterface(), "KNcloudBridge")

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    binding.pbWeb.visibility = View.VISIBLE
                    binding.pbWeb.progress = newProgress
                } else {
                    binding.pbWeb.visibility = View.INVISIBLE
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                if (!title.isNullOrBlank() && !title.contains("http", ignoreCase = true)) {
                    this@RegisterWebActivity.title = title
                }
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                injectAuthInterceptor(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectAuthInterceptor(view)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase()
                if (scheme == "kncloud" || scheme == "v2rayng") {
                    handleCustomScheme(uri)
                    return true
                }
                return false
            }
        }
    }

    private fun injectAuthInterceptor(view: WebView?) {
        val jsCode = """
            (function() {
                if (window.__kncloud_injected) return;
                window.__kncloud_injected = true;

                // 1. Hook Fetch API
                const origFetch = window.fetch;
                if (origFetch) {
                    window.fetch = async function(...args) {
                        const response = await origFetch.apply(this, args);
                        try {
                            const url = (args[0] || '').toString();
                            if (url.includes('/api/v1/passport/auth/register') || url.includes('/api/v1/passport/auth/login')) {
                                const clone = response.clone();
                                clone.json().then(data => {
                                    if (data && data.data) {
                                        const token = data.data.auth_data || data.data.token;
                                        if (token) {
                                            window.KNcloudBridge.onAuthSuccess(token, '');
                                        }
                                    }
                                }).catch(e => {});
                            }
                        } catch(e) {}
                        return response;
                    };
                }

                // 2. Hook XMLHttpRequest
                const origOpen = XMLHttpRequest.prototype.open;
                const origSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method, url) {
                    this.__reqUrl = url;
                    return origOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function(data) {
                    this.addEventListener('load', function() {
                        try {
                            if (this.__reqUrl && (this.__reqUrl.includes('/api/v1/passport/auth/register') || this.__reqUrl.includes('/api/v1/passport/auth/login'))) {
                                const resp = JSON.parse(this.responseText);
                                if (resp && resp.data) {
                                    const token = resp.data.auth_data || resp.data.token;
                                    if (token) {
                                        window.KNcloudBridge.onAuthSuccess(token, '');
                                    }
                                }
                            }
                        } catch(e) {}
                    });
                    return origSend.apply(this, arguments);
                };

                // 3. Periodic LocalStorage inspection for session tokens
                setInterval(function() {
                    try {
                        for (let i = 0; i < localStorage.length; i++) {
                            const key = localStorage.key(i);
                            const val = localStorage.getItem(key);
                            if (val && (key.toLowerCase().includes('auth') || key.toLowerCase().includes('token') || key.toLowerCase().includes('user'))) {
                                try {
                                    const parsed = JSON.parse(val);
                                    const token = parsed.auth_data || parsed.token;
                                    if (token) {
                                        window.KNcloudBridge.onAuthSuccess(token, parsed.email || '');
                                        return;
                                    }
                                } catch(_) {}
                                if (val.length > 20 && !val.includes('{') && !val.includes(' ') && (key === 'auth_data' || key === 'token' || key === 'auth_token')) {
                                    window.KNcloudBridge.onAuthSuccess(val, '');
                                    return;
                                }
                            }
                        }
                    } catch(e) {}
                }, 1000);
            })();
        """.trimIndent()

        view?.evaluateJavascript(jsCode, null)
    }

    private fun handleCustomScheme(uri: Uri) {
        val token = uri.getQueryParameter("token") ?: uri.getQueryParameter("auth_data")
        val email = uri.getQueryParameter("email").orEmpty()
        if (!token.isNullOrBlank()) {
            handleLoginWithToken(token, email)
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun onAuthSuccess(token: String?, email: String?) {
            if (token.isNullOrBlank()) return
            handleLoginWithToken(token, email.orEmpty())
        }
    }

    private fun handleLoginWithToken(token: String, email: String) {
        if (!isHandlingAuth.compareAndSet(false, true)) return

        lifecycleScope.launch(Dispatchers.IO) {
            val domain = MmkvManager.getApiDomain()
            MmkvManager.saveUserLogin(email, token, domain)

            withContext(Dispatchers.Main) {
                toast(R.string.login_fetching_sub)
            }

            val subResult = KNcloudAuthService.getSubscribeUrl(domain, token)
            if (subResult.success && !subResult.subscribeUrl.isNullOrBlank()) {
                KNcloudAuthService.importAndSyncSubscription(subResult.subscribeUrl)
            }

            withContext(Dispatchers.Main) {
                toastSuccess(R.string.login_success)
                val intent = Intent(this@RegisterWebActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }
        }
    }

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        try {
            binding.webView.stopLoading()
            binding.webView.destroy()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
