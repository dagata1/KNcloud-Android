package com.v2ray.ang.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityLoginBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.KNcloudAuthService
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.boot.BootVpn
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class LoginActivity : BaseActivity() {

    private val binding by lazy { ActivityLoginBinding.inflate(layoutInflater) }

    /**
     * Login bootstrap VPN: when the official site is not reachable directly, the login actions
     * (web auth in the browser, in-app register page, panel login) first bring up a temporary VPN
     * that sends only kncloud.top through the built-in boot node (everything else direct). It is
     * stopped once the login completed (or this screen is left). If the user declines the VPN
     * permission, the actions go ahead as before (panel calls then use the in-app BootProxy).
     */
    private var directProbe: Deferred<Boolean>? = null
    private var bootVpnDeclined = false
    private var pendingAfterBootVpn: (() -> Unit)? = null

    private val requestBootVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val action = pendingAfterBootVpn
        pendingAfterBootVpn = null
        val granted = it.resultCode == RESULT_OK || VpnService.prepare(this) == null
        Log.i(AppConfig.TAG, "Boot VPN permission result=${it.resultCode} granted=$granted")
        if (granted) {
            startBootVpnThen(action)
        } else {
            bootVpnDeclined = true
            setLoading(false)
            action?.invoke()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If user is already logged in, redirect directly to MainActivity
        if (MmkvManager.isUserLoggedIn()) {
            navigateToMain()
            return
        }

        setContentView(binding.root)

        initViews()
        startDirectProbe()
        fetchDomainAsync()
    }

    override fun onDestroy() {
        // Leaving the login screen for good (logged in, or the user backed out): the temporary
        // bootstrap VPN has no further use. A normal connection is never touched.
        if (isFinishing) {
            val app = applicationContext
            Thread { BootVpn.stopAndCleanup(app) }.start()
        }
        super.onDestroy()
    }

    private fun startDirectProbe() {
        if (!BootVpn.available || BootVpn.isUp(this)) return
        directProbe = lifecycleScope.async(Dispatchers.IO) { KNcloudAuthService.probeDirect() }
    }

    /**
     * Runs [action] once the official site is reachable: directly, or through the login bootstrap
     * VPN (asking for the VPN permission first if needed). Falls back to running [action] as is.
     */
    private fun withBootVpn(action: () -> Unit) {
        if (!BootVpn.available || bootVpnDeclined || BootVpn.isUp(this)) {
            action()
            return
        }
        setLoading(true)
        lifecycleScope.launch {
            val probe = directProbe ?: lifecycleScope.async(Dispatchers.IO) { KNcloudAuthService.probeDirect() }.also { directProbe = it }
            val reachable = withTimeoutOrNull(6000L) { probe.await() } ?: false
            if (reachable) {
                setLoading(false)
                action()
                return@launch
            }
            // Probe failed once: probe again next time instead of reusing the stale result
            directProbe = null
            val prepare = try { VpnService.prepare(this@LoginActivity) } catch (e: Exception) { null }
            if (prepare == null) {
                startBootVpnThen(action)
            } else {
                pendingAfterBootVpn = action
                try {
                    requestBootVpnPermission.launch(prepare)
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "Cannot request VPN permission for boot VPN", e)
                    pendingAfterBootVpn = null
                    bootVpnDeclined = true
                    setLoading(false)
                    action()
                }
            }
        }
    }

    private fun startBootVpnThen(action: (() -> Unit)?) {
        setLoading(true)
        lifecycleScope.launch {
            if (BootVpn.start(applicationContext)) {
                // the core runs in the :vpnCore process: wait for its VPN network to come up
                val up = withTimeoutOrNull(8000L) {
                    while (!BootVpn.isVpnNetworkUp(applicationContext)) delay(100)
                    true
                } ?: false
                Log.w(AppConfig.TAG, "Login bootstrap VPN up=$up")
                delay(300)
            }
            setLoading(false)
            action?.invoke()
        }
    }

    override fun onResume() {
        super.onResume()
        if (MmkvManager.isUserLoggedIn()) {
            navigateToMain()
        }
    }

    private fun initViews() {
        // Pre-fill email if previously entered
        val savedEmail = MmkvManager.getUserEmail()
        if (!savedEmail.isNullOrBlank()) {
            binding.etEmail.setText(savedEmail)
            binding.etPassword.requestFocus()
        }

        // Handle keyboard "Done" action on password field
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                performLogin()
                true
            } else {
                false
            }
        }

        // Login button
        binding.btnLogin.setOnClickListener {
            performLogin()
        }

        // Official website authorization login button: opens default external browser
        binding.btnWebAuth.setOnClickListener {
            withBootVpn {
                val domain = MmkvManager.getApiDomain()
                val authUrl = "$domain/#/login?from=app_auth"
                MmkvManager.markWebAuthStarted()
                Utils.openUri(this, authUrl)
            }
        }

        // Register button: opens in-app web registration
        binding.btnRegister.setOnClickListener {
            withBootVpn {
                startActivity(Intent(this, RegisterWebActivity::class.java))
            }
        }
    }

    private fun fetchDomainAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            KNcloudAuthService.fetchDynamicDomain()
        }
    }

    private fun performLogin() {
        // Reset errors
        binding.tilEmail.error = null
        binding.tilPassword.error = null

        val email = binding.etEmail.text?.toString()?.trim().orEmpty()
        val password = binding.etPassword.text?.toString()?.trim().orEmpty()

        if (email.isEmpty()) {
            binding.tilEmail.error = getString(R.string.login_empty_email)
            binding.etEmail.requestFocus()
            return
        }

        if (password.isEmpty()) {
            binding.tilPassword.error = getString(R.string.login_empty_password)
            binding.etPassword.requestFocus()
            return
        }

        withBootVpn { performLoginRequest(email, password) }
    }

    private fun performLoginRequest(email: String, password: String) {
        setLoading(true)

        lifecycleScope.launch(Dispatchers.IO) {
            // Login bootstrap (if a call has to use it) stays up for the whole flow and is stopped
            // at its end: login -> getSubscribe -> user info -> subscription download.
            val loginResult = KNcloudAuthService.bootSession {
                // 1. Perform Login
                val loginResult = KNcloudAuthService.login(email, password)
                if (loginResult.success && !loginResult.token.isNullOrBlank()) {
                    lifecycleScope.launch(Dispatchers.Main) { toast(R.string.login_fetching_sub) }

                    // 2. Fetch Subscription URL & Metadata
                    val subResult = KNcloudAuthService.getSubscribeUrl(loginResult.domain, loginResult.token)
                    if (subResult.planName.isNullOrBlank()) {
                        KNcloudAuthService.fetchUserInfo(loginResult.domain, loginResult.token)
                    }
                    if (subResult.success && !subResult.subscribeUrl.isNullOrBlank()) {
                        // 3. Import & sync subscription nodes
                        KNcloudAuthService.importAndSyncSubscription(subResult.subscribeUrl)
                    }
                }
                loginResult
            }

            if (!loginResult.success || loginResult.token.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    toastError(loginResult.message ?: getString(R.string.login_failed))
                }
                return@launch
            }

            // Logged in and subscription imported: the bootstrap VPN is no longer needed
            BootVpn.stopAndCleanup(applicationContext)

            withContext(Dispatchers.Main) {
                setLoading(false)
                toastSuccess(R.string.login_success)
                navigateToMain()
            }
        }
    }

    private fun setLoading(isLoading: Boolean) {
        binding.pbLoading.visibility = if (isLoading) View.VISIBLE else View.INVISIBLE
        binding.btnLogin.isEnabled = !isLoading
        binding.btnWebAuth.isEnabled = !isLoading
        binding.btnRegister.isEnabled = !isLoading
        binding.etEmail.isEnabled = !isLoading
        binding.etPassword.isEnabled = !isLoading
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
