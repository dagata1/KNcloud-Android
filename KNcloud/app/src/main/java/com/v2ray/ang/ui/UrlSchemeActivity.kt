package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityLogcatBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.KNcloudAuthService
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.TrustedDomain
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
                        confirmInstallConfig(shareUrl, uri?.fragment)
                        return
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
     *
     * Any web page can fire this link, so the domain and subscription URL must be
     * trusted KNcloud hosts and the user has to confirm before anything is saved.
     */
    private fun handleOneClickLogin(uri: Uri?) {
        val token = uri?.getQueryParameter("token")
            ?: uri?.getQueryParameter("auth_data")
            ?: uri?.getQueryParameter("auth_token")
            ?: uri?.getQueryParameter("auth")

        var email = uri?.getQueryParameter("email")
            ?: uri?.getQueryParameter("user")
            ?: uri?.getQueryParameter("account").orEmpty()

        if (email.isBlank() && !token.isNullOrBlank()) {
            email = KNcloudAuthService.extractEmailFromJwt(token).orEmpty()
        }

        val domainParam = uri?.getQueryParameter("domain")
            ?: uri?.getQueryParameter("api_domain")

        val directSubUrlParam = uri?.getQueryParameter("sub_url")
            ?: uri?.getQueryParameter("subscribe_url")
            ?: uri?.getQueryParameter("sub")

        if (token.isNullOrBlank()) {
            failAndGoHome(R.string.login_failed)
            return
        }

        val extraHosts = MmkvManager.getTrustedExtraHosts()
        val domain = if (domainParam.isNullOrBlank()) {
            MmkvManager.getApiDomain()
        } else {
            TrustedDomain.normalizeTrustedDomain(domainParam, extraHosts)
        }
        if (domain == null) {
            Log.w(AppConfig.TAG, "Rejected one-click login for untrusted domain: $domainParam")
            failAndGoHome(R.string.login_untrusted_domain)
            return
        }
        // A fallback subscription URL is only used when it lives on a trusted host
        val directSubUrl = directSubUrlParam?.takeIf { TrustedDomain.isTrustedUrl(it, extraHosts) }

        val host = TrustedDomain.hostOf(domain).orEmpty()
        val account = email.ifBlank { getString(R.string.login_confirm_unknown_account) }
        AlertDialog.Builder(this)
            .setTitle(R.string.login_confirm_title)
            .setMessage(getString(R.string.login_confirm_message, account, host))
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> performOneClickLogin(token, email, domain, directSubUrl) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> goHome() }
            .show()
    }

    private fun performOneClickLogin(token: String, email: String, domain: String, directSubUrl: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            // Save initial user login state
            MmkvManager.saveUserLogin(email, token, domain)

            // Fetch subscription & user info
            val subResult = KNcloudAuthService.getSubscribeUrl(domain, token)
            if (subResult.success) {
                if (!subResult.email.isNullOrBlank() && (email.isBlank() || MmkvManager.getUserEmail().isNullOrBlank())) {
                    MmkvManager.encodeSettings(AppConfig.PREF_USER_EMAIL, subResult.email)
                }
                if (subResult.planName.isNullOrBlank()) {
                    KNcloudAuthService.fetchUserInfo(domain, token)
                }
                if (!subResult.subscribeUrl.isNullOrBlank()) {
                    KNcloudAuthService.importAndSyncSubscription(subResult.subscribeUrl)
                } else if (!directSubUrl.isNullOrBlank()) {
                    KNcloudAuthService.importAndSyncSubscription(directSubUrl)
                }
            } else if (!directSubUrl.isNullOrBlank()) {
                KNcloudAuthService.fetchUserInfo(domain, token)
                KNcloudAuthService.importAndSyncSubscription(directSubUrl)
            }

            withContext(Dispatchers.Main) {
                toast(R.string.login_success)
                startActivity(Intent(this@UrlSchemeActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
        }
    }

    /**
     * Config links can come from any web page, so ask before importing nodes.
     */
    private fun confirmInstallConfig(shareUrl: String, fragment: String?) {
        if (shareUrl.isBlank()) {
            failAndGoHome(R.string.toast_failure)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.import_config_confirm_title)
            .setMessage(R.string.import_config_confirm_message)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> parseUri(shareUrl, fragment) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> goHome() }
            .show()
    }

    private fun failAndGoHome(messageRes: Int) {
        toastError(messageRes)
        goHome()
    }

    private fun goHome() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
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
