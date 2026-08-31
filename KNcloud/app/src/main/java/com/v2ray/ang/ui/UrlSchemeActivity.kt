package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityLogcatBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.KNcloudAuthService
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder

class UrlSchemeActivity : BaseActivity() {
    private val binding by lazy { ActivityLogcatBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        try {
            intent.apply {
                if (action == Intent.ACTION_SEND) {
                    if ("text/plain" == type) {
                        intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                            parseUri(it, null)
                        }
                    }
                } else if (action == Intent.ACTION_VIEW) {
                    val uri: Uri? = intent.data
                    val host = uri?.host?.lowercase().orEmpty()
                    val path = uri?.path?.lowercase().orEmpty()

                    val hasToken = uri?.getQueryParameter("token") != null
                            || uri?.getQueryParameter("auth_data") != null
                            || uri?.getQueryParameter("auth_token") != null
                            || uri?.getQueryParameter("auth") != null

                    if (host == "install-config" || path.contains("install-config")) {
                        val shareUrl = uri?.getQueryParameter("url").orEmpty()
                        parseUri(shareUrl, uri?.fragment)
                    } else if (hasToken || host in listOf("auth", "login", "register", "oneclick", "quick-login", "oauth", "callback", "sso")
                        || path.contains("login") || path.contains("auth") || path.contains("oauth")) {
                        handleOneClickLogin(uri)
                        return
                    } else if (host == "install-sub") {
                        // Subscriptions are strictly bound to KNcloud login account
                        toastError(R.string.toast_failure)
                    } else {
                        toastError(R.string.toast_failure)
                    }
                }
            }

            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Error processing URL scheme", e)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    /**
     * Handles one-click login from web / URL scheme:
     * e.g. kncloud://login?token={TOKEN}&email={EMAIL}&domain={DOMAIN}&sub_url={SUB_URL}
     */
    private fun handleOneClickLogin(uri: Uri?) {
        val token = uri?.getQueryParameter("token")
            ?: uri?.getQueryParameter("auth_data")
            ?: uri?.getQueryParameter("auth_token")
            ?: uri?.getQueryParameter("auth")

        val email = uri?.getQueryParameter("email")
            ?: uri?.getQueryParameter("user")
            ?: uri?.getQueryParameter("account").orEmpty()

        val domainParam = uri?.getQueryParameter("domain")
            ?: uri?.getQueryParameter("api_domain")

        val directSubUrl = uri?.getQueryParameter("sub_url")
            ?: uri?.getQueryParameter("subscribe_url")
            ?: uri?.getQueryParameter("sub")

        if (!token.isNullOrBlank()) {
            lifecycleScope.launch(Dispatchers.IO) {
                var domain = domainParam?.trim().orEmpty()
                if (domain.isNotEmpty() && !domain.startsWith("http://") && !domain.startsWith("https://")) {
                    domain = "https://$domain"
                }
                if (domain.endsWith("/")) {
                    domain = domain.dropLast(1)
                }
                if (domain.isBlank()) {
                    domain = MmkvManager.getApiDomain()
                }

                // Save user login state
                MmkvManager.saveUserLogin(email, token, domain)

                // Sync subscription
                if (!directSubUrl.isNullOrBlank()) {
                    KNcloudAuthService.importAndSyncSubscription(directSubUrl)
                } else {
                    val sub = KNcloudAuthService.getSubscribeUrl(domain, token)
                    if (sub.success && !sub.subscribeUrl.isNullOrBlank()) {
                        KNcloudAuthService.importAndSyncSubscription(sub.subscribeUrl)
                    }
                }

                withContext(Dispatchers.Main) {
                    toast(R.string.login_success)
                    startActivity(Intent(this@UrlSchemeActivity, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                }
            }
        } else {
            toastError(R.string.login_failed)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun parseUri(uriString: String?, fragment: String?) {
        if (uriString.isNullOrEmpty()) {
            return
        }
        Log.i(AppConfig.TAG, uriString)

        var decodedUrl = URLDecoder.decode(uriString, "UTF-8")
        val uri = Uri.parse(decodedUrl)
        if (uri != null) {
            if (uri.fragment.isNullOrEmpty() && !fragment.isNullOrEmpty()) {
                decodedUrl += "#${fragment}"
            }
            Log.i(AppConfig.TAG, decodedUrl)
            lifecycleScope.launch(Dispatchers.IO) {
                val (count, countSub) = AngConfigManager.importBatchConfig(decodedUrl, "", false)
                withContext(Dispatchers.Main) {
                    if (count + countSub > 0) {
                        toast(R.string.import_subscription_success)
                    } else {
                        toast(R.string.import_subscription_failure)
                    }
                    startActivity(Intent(this@UrlSchemeActivity, MainActivity::class.java))
                    finish()
                }
            }
        }
    }
}
