package com.v2ray.ang.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MigrateManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : BaseActivity() {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    private val adapter by lazy { MainRecyclerAdapter(this) }
    private var mItemTouchHelper: ItemTouchHelper? = null

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }

    val mainViewModel: MainViewModel by viewModels()

    // register activity result for requesting permission
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                when (pendingAction) {
                    Action.IMPORT_QR_CODE_CONFIG ->
                        scanQRCodeForConfig.launch(Intent(this, ScannerActivity::class.java))

                    Action.READ_CONTENT_FROM_URI ->
                        chooseFileForCustomConfig.launch(Intent.createChooser(Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }, getString(R.string.title_file_chooser)))

                    Action.POST_NOTIFICATIONS -> {}
                    else -> {}
                }
            } else {
                toast(R.string.toast_permission_denied)
            }
            pendingAction = Action.NONE
        }

    private var pendingAction: Action = Action.NONE

    enum class Action {
        NONE,
        IMPORT_QR_CODE_CONFIG,
        READ_CONTENT_FROM_URI,
        POST_NOTIFICATIONS
    }

    private val chooseFileForCustomConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val uri = it.data?.data
        if (it.resultCode == RESULT_OK && uri != null) {
            readContentFromUri(uri)
        }
    }

    private val scanQRCodeForConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            importBatchConfig(it.data?.getStringExtra("SCAN_RESULT"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if user is logged in; if not, show login page only
        if (!MmkvManager.isUserLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(binding.root)

        // Top bar buttons
        binding.btnTopSettings.setOnClickListener {
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .putExtra("isRunning", mainViewModel.isRunning.value == true)
            )
        }

        binding.btnTopRefresh.setOnClickListener {
            importConfigViaSub()
        }

        binding.btnTopLogout.setOnClickListener {
            showLogoutDialog()
        }

        // ==========================================
        // Simple Mode Click Listeners
        // ==========================================
        binding.btnConnectToggle.setOnClickListener {
            toggleV2RayConnection()
        }

        binding.layoutTestSimple.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                setTestState(getString(R.string.connection_test_testing))
                mainViewModel.testCurrentServerRealPing()
            } else {
                toggleV2RayConnection()
            }
        }

        binding.layoutNodeSelector.setOnClickListener {
            NodeSelectorBottomSheet.show(supportFragmentManager)
        }

        binding.btnGoWebsite.setOnClickListener {
            openSubscribeWebPage()
        }

        // Refresh Subscription Button
        binding.btnRefreshSub.setOnClickListener {
            importConfigViaSub()
        }

        // ==========================================
        // Classic Mode Click Listeners & RecyclerView
        // ==========================================
        binding.fab.setOnClickListener {
            toggleV2RayConnection()
        }

        binding.layoutTest.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                setTestState(getString(R.string.connection_test_testing))
                mainViewModel.testCurrentServerRealPing()
            }
        }

        binding.recyclerView.setHasFixedSize(true)
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)) {
            binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
        } else {
            binding.recyclerView.layoutManager = GridLayoutManager(this, 1)
        }
        binding.recyclerView.adapter = adapter

        mItemTouchHelper = ItemTouchHelper(SimpleItemTouchHelperCallback(adapter))
        mItemTouchHelper?.attachToRecyclerView(binding.recyclerView)

        setupEmptyStateView()
        setupViewModel()
        updateModeVisibility()
        migrateLegacy()
        autoTestAllRealPing()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingAction = Action.POST_NOTIFICATIONS
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(false)
            }
        })
    }

    private fun toggleV2RayConnection() {
        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        } else if ((MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) ?: VPN) == VPN) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startV2Ray()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            startV2Ray()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setupViewModel() {
        mainViewModel.updateListAction.observe(this) { index ->
            if (index >= 0) {
                adapter.notifyItemChanged(index)
            } else {
                adapter.notifyDataSetChanged()
            }
            updateSelectedNodeUI()
            updateEmptyState()
        }

        mainViewModel.updateTestResultAction.observe(this) {
            setTestState(it)
            updateSelectedNodeUI()
        }

        mainViewModel.isRunning.observe(this) { isRunning ->
            adapter.isRunning = isRunning
            updateConnectionUI(isRunning)
        }

        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    /**
     * Toggles between Simple Card Dashboard Mode and Classic List Mode
     */
    private fun updateModeVisibility() {
        val isSimpleMode = MmkvManager.decodeSettingsBool(AppConfig.PREF_SIMPLE_MODE, true)
        binding.scrollSimpleMode.isVisible = isSimpleMode
        binding.layoutClassicMode.isVisible = !isSimpleMode

        if (isSimpleMode) {
            binding.btnTopRefresh.isVisible = false
            binding.topCapsuleDivider.isVisible = false
            binding.layoutTopCapsule.setBackgroundResource(R.drawable.bg_top_bar_circle)
            updateSelectedNodeUI()
        } else {
            binding.btnTopRefresh.isVisible = true
            binding.topCapsuleDivider.isVisible = true
            binding.layoutTopCapsule.setBackgroundResource(R.drawable.bg_top_bar_capsule)
            adapter.notifyDataSetChanged()
        }
        updateSubscriptionInfo()
        updateEmptyState()
        updateConnectionUI(mainViewModel.isRunning.value == true)
    }

    private fun updateConnectionUI(isRunning: Boolean) {
        // Simple Mode Hero Connect Button
        if (isRunning) {
            binding.flConnectOuter.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.connect_btn_active_ring))
            binding.btnConnectToggle.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.connect_btn_active_bg))
            binding.ivConnectIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.connect_btn_active_icon))
            binding.tvConnectionStatus.text = getString(R.string.connect_state_connected)
            binding.tvTestStateSimple.text = getString(R.string.connect_tap_to_disconnect)

            // Classic Mode FAB & Test Bar
            binding.fab.setImageResource(R.drawable.ic_stop_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
            binding.fab.contentDescription = getString(R.string.action_stop_service)
            setTestState(getString(R.string.connection_connected))
            binding.layoutTest.isFocusable = true
        } else {
            binding.flConnectOuter.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.connect_btn_idle_ring))
            binding.btnConnectToggle.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.connect_btn_idle_bg))
            binding.ivConnectIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.connect_btn_idle_icon))
            binding.tvConnectionStatus.text = getString(R.string.connect_state_idle)
            binding.tvTestStateSimple.text = getString(R.string.connect_tap_to_connect)

            // Classic Mode FAB & Test Bar
            binding.fab.setImageResource(R.drawable.ic_play_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
            binding.fab.contentDescription = getString(R.string.tasker_start_service)
            setTestState(getString(R.string.connection_not_connected))
            binding.layoutTest.isFocusable = false
        }
    }

    /**
     * Updates the Selected Node Display on the Hero Card (Simple Mode)
     */
    fun updateSelectedNodeUI() {
        val servers = mainViewModel.serversCache
        if (servers.isEmpty()) {
            binding.tvSelectedNodeName.text = getString(R.string.no_node_selected)
            binding.tvSelectedNodeType.isVisible = false
            binding.tvSelectedNodePing.isVisible = false
            return
        }

        var selectServerGuid = MmkvManager.getSelectServer()
        var currentServer = servers.find { it.guid == selectServerGuid }

        // If no server selected or selected server doesn't exist, default to the first server
        if (currentServer == null) {
            currentServer = servers.first()
            selectServerGuid = currentServer.guid
            MmkvManager.setSelectServer(selectServerGuid)
        }

        val profile = currentServer.profile
        binding.tvSelectedNodeName.text = profile.remarks.ifBlank { getString(R.string.app_name) }
        binding.tvSelectedNodeType.text = profile.configType.name
        binding.tvSelectedNodeType.isVisible = true

        // Ping Delay Badge
        val aff = MmkvManager.decodeServerAffiliationInfo(currentServer.guid)
        val delayMillis = aff?.testDelayMillis ?: 0L
        val delayStr = aff?.getTestDelayString().orEmpty()

        if (delayStr.isNotBlank()) {
            binding.tvSelectedNodePing.isVisible = true
            binding.tvSelectedNodePing.text = delayStr
            when {
                delayMillis < 0L -> {
                    binding.tvSelectedNodePing.setTextColor(ContextCompat.getColor(this, R.color.colorPingRed))
                }
                delayMillis in 1..150 -> {
                    binding.tvSelectedNodePing.setTextColor(ContextCompat.getColor(this, R.color.colorPingGreen))
                }
                delayMillis in 151..300 -> {
                    binding.tvSelectedNodePing.setTextColor(ContextCompat.getColor(this, R.color.colorPingYellow))
                }
                else -> {
                    binding.tvSelectedNodePing.setTextColor(ContextCompat.getColor(this, R.color.colorPingRed))
                }
            }
        } else {
            binding.tvSelectedNodePing.isVisible = false
        }
    }

    /**
     * Called when a node is chosen from the Node Selector BottomSheet
     */
    fun onNodeSelected(guid: String) {
        val currentSelected = MmkvManager.getSelectServer()
        if (guid != currentSelected) {
            MmkvManager.setSelectServer(guid)
            updateSelectedNodeUI()
            adapter.notifyDataSetChanged()
            if (mainViewModel.isRunning.value == true) {
                restartV2Ray()
            }
        }
    }

    private fun migrateLegacy() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = MigrateManager.migrateServerConfig2Profile()
            launch(Dispatchers.Main) {
                if (result) {
                    toast(getString(R.string.migration_success))
                    mainViewModel.reloadServerList()
                    updateSelectedNodeUI()
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun autoTestAllRealPing() {
        lifecycleScope.launch {
            delay(1000L) // Wait for core environment and server list to initialize
            if (mainViewModel.serversCache.isNotEmpty()) {
                mainViewModel.testAllRealPing()
            }
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        V2RayServiceManager.startVService(this)
    }

    private fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    public override fun onResume() {
        super.onResume()
        if (!MmkvManager.isUserLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        mainViewModel.reloadServerList()
        updateModeVisibility()
    }

    private fun updateEmptyState() {
        val isEmpty = mainViewModel.serversCache.isEmpty()
        val isSimpleMode = MmkvManager.decodeSettingsBool(AppConfig.PREF_SIMPLE_MODE, true)

        if (isSimpleMode) {
            binding.layoutEmptyNodesSimple.isVisible = isEmpty
            binding.cardMainDashboard.isVisible = !isEmpty
        } else {
            binding.layoutEmptyNodesClassic.isVisible = isEmpty
            binding.recyclerView.isVisible = !isEmpty
        }
    }

    private fun setupEmptyStateView() {
        val fullText = getString(R.string.empty_no_nodes_full_text)
        val keyword = getString(R.string.empty_subscribe_keyword)
        val spannable = SpannableString(fullText)
        val startIndex = fullText.indexOf(keyword)
        if (startIndex >= 0) {
            val clickableSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    openSubscribeWebPage()
                }

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.color = ContextCompat.getColor(this@MainActivity, R.color.color_fab_active)
                    ds.isUnderlineText = true
                }
            }
            spannable.setSpan(clickableSpan, startIndex, startIndex + keyword.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            binding.tvEmptyPromptSimple.text = spannable
            binding.tvEmptyPromptSimple.movementMethod = LinkMovementMethod.getInstance()
            binding.tvEmptyPromptClassic.text = spannable
            binding.tvEmptyPromptClassic.movementMethod = LinkMovementMethod.getInstance()
        } else {
            binding.tvEmptyPromptSimple.text = fullText
            binding.tvEmptyPromptClassic.text = fullText
        }

        binding.btnGoSubscribeSimple.setOnClickListener {
            openSubscribeWebPage()
        }
        binding.btnGoSubscribeClassic.setOnClickListener {
            openSubscribeWebPage()
        }
    }

    private fun openSubscribeWebPage() {
        val planUrl = "${MmkvManager.getApiDomain()}/#/plan"
        startActivity(
            Intent(this, RegisterWebActivity::class.java)
                .putExtra(RegisterWebActivity.EXTRA_URL, planUrl)
                .putExtra(RegisterWebActivity.EXTRA_TITLE, getString(R.string.empty_subscribe_btn))
        )
    }

    private fun updateSubscriptionInfo() {
        val info = MmkvManager.getSubscriptionInfo()
        if (info != null && info.hasData()) {
            val title = if (info.subName.isNotBlank()) info.subName else getString(R.string.app_name)
            val formattedTraffic = info.getFormattedTraffic()
            val percent = info.calculateUsagePercent()
            val cleanResetDay = info.getCleanResetDay()
            val cleanExpireDate = info.getCleanExpireDate()

            // 1. Simple Mode
            binding.cardSubInfo.isVisible = true
            binding.tvSubTitle.text = title
            if (formattedTraffic.isNotBlank()) {
                binding.tvSubTraffic.text = formattedTraffic
                binding.tvSubTraffic.isVisible = true
            } else {
                binding.tvSubTraffic.isVisible = false
            }

            if (percent >= 0) {
                binding.tvSubPercent.text = "${percent}%"
                binding.tvSubPercent.isVisible = true
                binding.pbSubTraffic.isVisible = true
                binding.pbSubTraffic.progress = percent
            } else {
                binding.tvSubPercent.isVisible = false
                binding.pbSubTraffic.isVisible = false
            }

            if (cleanResetDay.isNotBlank()) {
                binding.tvSubReset.text = "下次重置：$cleanResetDay"
                binding.tvSubReset.isVisible = true
            } else {
                binding.tvSubReset.isVisible = false
            }

            if (cleanExpireDate.isNotBlank()) {
                binding.tvSubExpire.text = "套餐到期：$cleanExpireDate"
                binding.tvSubExpire.isVisible = true
            } else {
                binding.tvSubExpire.isVisible = false
            }

            // 2. Classic Mode
            binding.cardSubInfoClassic.isVisible = true
            binding.tvSubTitleClassic.text = title
            if (formattedTraffic.isNotBlank()) {
                binding.tvSubTrafficClassic.text = formattedTraffic
                binding.tvSubTrafficClassic.isVisible = true
            } else {
                binding.tvSubTrafficClassic.isVisible = false
            }

            if (percent >= 0) {
                binding.tvSubPercentClassic.text = "${percent}%"
                binding.tvSubPercentClassic.isVisible = true
                binding.pbSubTrafficClassic.isVisible = true
                binding.pbSubTrafficClassic.progress = percent
            } else {
                binding.tvSubPercentClassic.isVisible = false
                binding.pbSubTrafficClassic.isVisible = false
            }

            if (cleanResetDay.isNotBlank()) {
                binding.tvSubResetClassic.text = "下次重置：$cleanResetDay"
                binding.tvSubResetClassic.isVisible = true
            } else {
                binding.tvSubResetClassic.isVisible = false
            }

            if (cleanExpireDate.isNotBlank()) {
                binding.tvSubExpireClassic.text = "套餐到期：$cleanExpireDate"
                binding.tvSubExpireClassic.isVisible = true
            } else {
                binding.tvSubExpireClassic.isVisible = false
            }
        } else {
            binding.cardSubInfo.isVisible = false
            binding.cardSubInfoClassic.isVisible = false
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.sub_update -> {
            importConfigViaSub()
            true
        }

        R.id.settings -> {
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .putExtra("isRunning", mainViewModel.isRunning.value == true)
            )
            true
        }

        R.id.logout -> {
            showLogoutDialog()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_logout_title)
            .setMessage(R.string.dialog_logout_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (mainViewModel.isRunning.value == true) {
                    V2RayServiceManager.stopVService(this)
                }
                MmkvManager.clearUserLogin()
                MmkvManager.removeAllSubscriptions()
                MmkvManager.removeAllServer()
                val intent = Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Test TCP ping for all servers
     */
    fun pingAll() {
        toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
        mainViewModel.testAllTcping()
    }

    /**
     * Test Real ping for all servers
     */
    fun realPingAll() {
        toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
        mainViewModel.testAllRealPing()
    }

    private fun importManually(createConfigType: Int) {
        startActivity(
            Intent()
                .putExtra("createConfigType", createConfigType)
                .putExtra("subscriptionId", mainViewModel.subscriptionId)
                .setClass(this, ServerActivity::class.java)
        )
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(): Boolean {
        val permission = Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            scanQRCodeForConfig.launch(Intent(this, ScannerActivity::class.java))
        } else {
            pendingAction = Action.IMPORT_QR_CODE_CONFIG
            requestPermissionLauncher.launch(permission)
        }
        return true
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard(): Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        binding.pbWaiting.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                            updateSubscriptionInfo()
                            updateSelectedNodeUI()
                            updateEmptyState()
                            autoTestAllRealPing()
                        }

                        countSub > 0 -> {
                            updateSubscriptionInfo()
                        }
                        else -> toastError(R.string.toast_failure)
                    }
                    binding.pbWaiting.hide()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    binding.pbWaiting.hide()
                }
                Log.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    /**
     * import config from local config file
     */
    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }

    /**
     * import config from sub
     */
    private fun importConfigViaSub(): Boolean {
        binding.pbWaiting.show()

        lifecycleScope.launch(Dispatchers.IO) {
            val count = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (count > 0) {
                    toast(getString(R.string.title_update_config_count, count))
                    mainViewModel.reloadServerList()
                    updateSubscriptionInfo()
                    updateSelectedNodeUI()
                    updateEmptyState()
                    autoTestAllRealPing()
                } else {
                    toastError(R.string.toast_failure)
                }
                binding.pbWaiting.hide()
            }
        }
        return true
    }

    private fun exportAll() {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0)
                    toast(getString(R.string.title_export_config_count, ret))
                else
                    toastError(R.string.toast_failure)
                binding.pbWaiting.hide()
            }
        }
    }

    private fun delAllConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.pbWaiting.show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        updateSelectedNodeUI()
                        updateEmptyState()
                        toast(getString(R.string.title_del_config_count, ret))
                        binding.pbWaiting.hide()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.pbWaiting.show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        updateSelectedNodeUI()
                        updateEmptyState()
                        toast(getString(R.string.title_del_duplicate_config_count, ret))
                        binding.pbWaiting.hide()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delInvalidConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_invalid_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.pbWaiting.show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeInvalidServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        updateSelectedNodeUI()
                        updateEmptyState()
                        toast(getString(R.string.title_del_config_count, ret))
                        binding.pbWaiting.hide()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun sortByTestResults() {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                updateSelectedNodeUI()
                binding.pbWaiting.hide()
            }
        }
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            pendingAction = Action.READ_CONTENT_FROM_URI
            chooseFileForCustomConfig.launch(Intent.createChooser(intent, getString(R.string.title_file_chooser)))
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            try {
                contentResolver.openInputStream(uri).use { input ->
                    importBatchConfig(input?.bufferedReader()?.readText())
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to read content from URI", e)
            }
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
