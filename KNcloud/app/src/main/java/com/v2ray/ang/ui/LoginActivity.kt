package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityLoginBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.KNcloudAuthService
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : BaseActivity() {

    private val binding by lazy { ActivityLoginBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If user is already logged in, redirect directly to MainActivity
        if (MmkvManager.isUserLoggedIn()) {
            navigateToMain()
            return
        }

        setContentView(binding.root)

        initViews()
        fetchDomainAsync()
    }

    private fun initViews() {
        // Pre-fill email if previously entered
        val savedEmail = MmkvManager.getUserEmail()
        if (!savedEmail.isNullOrBlank()) {
            binding.etEmail.setText(savedEmail)
            binding.etPassword.requestFocus()
        }

        // Domain hint
        binding.tvDomainStatus.text = MmkvManager.getApiDomain()

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

        // Register button
        binding.btnRegister.setOnClickListener {
            openRegisterPage()
        }
    }

    private fun fetchDomainAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            val domain = KNcloudAuthService.fetchDynamicDomain()
            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) {
                    binding.tvDomainStatus.text = domain
                }
            }
        }
    }

    private fun openRegisterPage() {
        val domain = MmkvManager.getApiDomain()
        val registerUrl = "$domain/#/register"
        Utils.openUri(this, registerUrl)
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

        setLoading(true)

        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Perform Login
            val loginResult = KNcloudAuthService.login(email, password)

            if (!loginResult.success || loginResult.token.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    toastError(loginResult.message ?: getString(R.string.login_failed))
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                toast(R.string.login_fetching_sub)
            }

            // 2. Fetch Subscription URL
            val subResult = KNcloudAuthService.getSubscribeUrl(loginResult.domain, loginResult.token)
            if (subResult.success && !subResult.subscribeUrl.isNullOrBlank()) {
                // 3. Import & sync subscription nodes
                KNcloudAuthService.importAndSyncSubscription(subResult.subscribeUrl)
            }

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
