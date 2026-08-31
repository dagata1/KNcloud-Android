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
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlinx.coroutines.Job
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
import com.v2ray.ang.util.DialogUtil
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
            startV2RayWithConnectingState()
        }
    }

    val mainViewModel: MainViewModel by viewModels()

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    private var connectionState: ConnectionState = ConnectionState.DISCONNECTED
    private var connectingPulseAnimator: ObjectAnimator? = null
    private var connectingInnerAnimator: ObjectAnimator? = null
    private var connectingAlphaAnimator: ObjectAnimator? = null
    private var connectingTimeoutJob: Job? = null
    private var isSwitchingServer: Boolean = false

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

        binding.btnTopLogoutCircle.setOnClickListener {
            showLogoutDialog()
        }

        binding.btnTopRefresh.setOnClickListener {
            importConfigViaSub()
        }

        binding.btnTopPing.setOnClickListener {
            realPingAll()
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
        binding.btnConnectClassic.setOnClickListener {
            toggleV2RayConnection()
        }

        binding.btnGoWebsiteClassic.setOnClickListener {
            openSubscribeWebPage()
        }

        binding.recyclerView.setHasFixedSize(true)
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)) {
            val gridLayoutManager = GridLayoutManager(this, 2)
            gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (position == adapter.itemCount - 1) 2 else 1
                }
            }
            binding.recyclerView.layoutManager = gridLayoutManager
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
        isSwitchingServer = false
        if (connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.CONNECTED || mainViewModel.isRunning.value == true) {
            setConnectionState(ConnectionState.DISCONNECTED)
            V2RayServiceManager.stopVService(this)
        } else if (mainViewModel.serversCache.isEmpty() || MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
        } else if ((MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) ?: VPN) == VPN) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startV2RayWithConnectingState()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            startV2RayWithConnectingState()
        }
    }

    private fun startConnectingTimeout() {
        connectingTimeoutJob?.cancel()
        connectingTimeoutJob = lifecycleScope.launch {
            delay(8000L)
            if (connectionState == ConnectionState.CONNECTING) {
                val isSimpleMode = MmkvManager.decodeSettingsBool(AppConfig.PREF_SIMPLE_MODE, true)
                if (isSimpleMode) {
                    setConnectionState(ConnectionState.DISCONNECTED)
                    if (mainViewModel.isRunning.value == true) {
                        V2RayServiceManager.stopVService(this@MainActivity)
                    }
                    val selectServer = MmkvManager.getSelectServer().orEmpty()
                    if (selectServer.isNotEmpty()) {
                        MmkvManager.encodeServerTestDelayMillis(selectServer, -1L)
                    }
                    updateSelectedNodeUI()
                    toastError(R.string.toast_node_connection_failed)
                } else {
                    if (mainViewModel.isRunning.value == true) {
                        setConnectionState(ConnectionState.CONNECTED)
                    } else {
                        setConnectionState(ConnectionState.DISCONNECTED)
                    }
                }
            }
        }
    }

    private fun startV2RayWithConnectingState() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        setConnectionState(ConnectionState.CONNECTING)
        startConnectingTimeout()
        V2RayServiceManager.startVService(this)
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

        mainViewModel.updateTestResultAction.observe(this) { testResult ->
            updateSelectedNodeUI()

            val isSimpleMode = MmkvManager.decodeSettingsBool(AppConfig.PREF_SIMPLE_MODE, true)
            if (connectionState == ConnectionState.CONNECTING) {
                val selectServer = MmkvManager.getSelectServer().orEmpty()
                val aff = MmkvManager.decodeServerAffiliationInfo(selectServer)
                val isSuccess = (aff?.testDelayMillis ?: 0L) > 0L ||
                        (testResult?.contains(getString(R.string.connection_test_available).substringBefore("%")) == true)

                if (isSimpleMode) {
                    if (isSuccess && mainViewModel.isRunning.value == true) {
                        setConnectionState(ConnectionState.CONNECTED)
                    } else {
                        setConnectionState(ConnectionState.DISCONNECTED)
                        if (mainViewModel.isRunning.value == true) {
                            V2RayServiceManager.stopVService(this)
                        }
                        toastError(R.string.toast_node_connection_failed)
                    }
                } else {
                    if (mainViewModel.isRunning.value == true) {
                        setConnectionState(ConnectionState.CONNECTED)
                    }
                }
            }
        }

        mainViewModel.isRunning.observe(this) { isRunning ->
            adapter.isRunning = isRunning
            if (isRunning) {
                isSwitchingServer = false
                if (connectionState == ConnectionState.CONNECTING) {
                    mainViewModel.testCurrentServerRealPing()
                } else {
                    setConnectionState(ConnectionState.CONNECTED)
                }
            } else {
                if (!isSwitchingServer) {
                    setConnectionState(ConnectionState.DISCONNECTED)
                }
            }
            updateClassicConnectionUI(isRunning)
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
            binding.ivTopLogo.isVisible = true
            binding.btnTopLogoutCircle.isVisible = true
            binding.layoutTopCapsule.isVisible = false
            updateSelectedNodeUI()
        } else {
            binding.ivTopLogo.isVisible = false
            binding.btnTopLogoutCircle.isVisible = false
            binding.layoutTopCapsule.isVisible = true
            adapter.notifyDataSetChanged()
        }
        updateSubscriptionInfo()
        updateEmptyState()
        val isRunning = mainViewModel.isRunning.value == true
        if (isRunning && connectionState != ConnectionState.CONNECTING) {
            setConnectionState(ConnectionState.CONNECTED)
        } else if (!isRunning && !isSwitchingServer) {
            setConnectionState(ConnectionState.DISCONNECTED)
        }
        updateClassicConnectionUI(isRunning)
    }

    private fun setConnectionState(state: ConnectionState) {
        val previousState = connectionState
        connectionState = state
        when (state) {
            ConnectionState.CONNECTING -> {
                binding.tvConnectionStatus.text = getString(R.string.connect_state_connecting)
                binding.flConnectOuter.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.connect_btn_idle_ring))
                binding.btnConnectToggle.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.connect_btn_active_bg))
                binding.ivConnectIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.connect_btn_active_icon))

                binding.btnConnectClassic.text = getString(R.string.connect_state_connecting)
                binding.btnConnectClassic.setIconResource(R.drawable.ic_play_24dp)
                binding.btnConnectClassic.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))

                startConnectingAnimation()
            }
            ConnectionState.CONNECTED -> {
                stopConnectingAnimation()
                connectingTimeoutJob?.cancel()
                binding.tvConnectionStatus.text = getString(R.string.connect_state_connected)
                binding.flConnectOuter.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.connect_btn_active_ring))
                binding.btnConnectToggle.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.connect_btn_active_bg))
                binding.ivConnectIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.connect_btn_active_icon))

                binding.btnConnectClassic.text = getString(R.string.action_stop_service)
                binding.btnConnectClassic.setIconResource(R.drawable.ic_stop_24dp)
                binding.btnConnectClassic.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.colorPingRed))

                if (previousState == ConnectionState.CONNECTING) {
                    playConnectedSuccessAnimation()
                }
            }
            ConnectionState.DISCONNECTED -> {
                stopConnectingAnimation()
                connectingTimeoutJob?.cancel()
                binding.tvConnectionStatus.text = getString(R.string.connect_state_idle)
                binding.flConnectOuter.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.connect_btn_idle_ring))
                binding.btnConnectToggle.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.connect_btn_idle_bg))
                binding.ivConnectIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.connect_btn_idle_icon))

                binding.btnConnectClassic.text = getString(R.string.tasker_start_service)
                binding.btnConnectClassic.setIconResource(R.drawable.ic_play_24dp)
                binding.btnConnectClassic.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
            }
        }
    }

    private fun startConnectingAnimation() {
        stopConnectingAnimation()

        // 1. Show and spin the circular progress indicator around the button
        binding.cpConnecting.isVisible = true

        // 2. Inner Button subtle breathing pulse
        val innerScaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 0.95f)
        val innerScaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 0.95f)

        connectingInnerAnimator = ObjectAnimator.ofPropertyValuesHolder(binding.btnConnectToggle, innerScaleX, innerScaleY).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // 3. Center Icon breathing transparency and subtle scale
        val iconAlpha = PropertyValuesHolder.ofFloat(View.ALPHA, 1.0f, 0.6f)
        val iconScaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 0.92f)
        val iconScaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 0.92f)

        connectingAlphaAnimator = ObjectAnimator.ofPropertyValuesHolder(binding.ivConnectIcon, iconAlpha, iconScaleX, iconScaleY).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopConnectingAnimation() {
        binding.cpConnecting.isVisible = false

        connectingPulseAnimator?.cancel()
        connectingPulseAnimator = null
        connectingInnerAnimator?.cancel()
        connectingInnerAnimator = null
        connectingAlphaAnimator?.cancel()
        connectingAlphaAnimator = null

        binding.flConnectOuter.scaleX = 1.0f
        binding.flConnectOuter.scaleY = 1.0f
        binding.flConnectOuter.alpha = 1.0f

        binding.btnConnectToggle.scaleX = 1.0f
        binding.btnConnectToggle.scaleY = 1.0f

        binding.ivConnectIcon.scaleX = 1.0f
        binding.ivConnectIcon.scaleY = 1.0f
        binding.ivConnectIcon.alpha = 1.0f
    }

    private fun playConnectedSuccessAnimation() {
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.92f, 1.06f, 1.0f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.92f, 1.06f, 1.0f)
        ObjectAnimator.ofPropertyValuesHolder(binding.btnConnectToggle, scaleX, scaleY).apply {
            duration = 380
            interpolator = OvershootInterpolator(2.0f)
            start()
        }
    }

    private fun updateClassicConnectionUI(isRunning: Boolean) {
        if (isRunning) {
            binding.btnConnectClassic.text = getString(R.string.action_stop_service)
            binding.btnConnectClassic.setIconResource(R.drawable.ic_stop_24dp)
            binding.btnConnectClassic.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.colorPingRed))
            binding.btnConnectClassic.contentDescription = getString(R.string.action_stop_service)
        } else {
            binding.btnConnectClassic.text = getString(R.string.tasker_start_service)
            binding.btnConnectClassic.setIconResource(R.drawable.ic_play_24dp)
            binding.btnConnectClassic.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
            binding.btnConnectClassic.contentDescription = getString(R.string.tasker_start_service)
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
            if (delayMillis < 0L) {
                binding.tvSelectedNodePing.text = "error"
                binding.tvSelectedNodePing.setTextColor(ContextCompat.getColor(this, R.color.colorPingRed))
            } else {
                binding.tvSelectedNodePing.text = delayStr
                when (delayMillis) {
                    in 1..150 -> {
                        binding.tvSelectedNodePing.setTextColor(ContextCompat.getColor(this, R.color.colorPingGreen))
                    }
                    in 151..300 -> {
                        binding.tvSelectedNodePing.setTextColor(ContextCompat.getColor(this, R.color.colorPingYellow))
                    }
                    else -> {
                        binding.tvSelectedNodePing.setTextColor(ContextCompat.getColor(this, R.color.colorPingRed))
                    }
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
        isSwitchingServer = true
        setConnectionState(ConnectionState.CONNECTING)
        startConnectingTimeout()
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
            binding.cardMainDashboard.isVisible = true
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
            binding.tvEmptyPromptClassic.text = spannable
            binding.tvEmptyPromptClassic.movementMethod = LinkMovementMethod.getInstance()
        } else {
            binding.tvEmptyPromptClassic.text = fullText
        }

        binding.btnGoSubscribeClassic.setOnClickListener {
            openSubscribeWebPage()
        }
    }

    private fun openSubscribeWebPage() {
        val websiteUrl = MmkvManager.getApiDomain()
        Utils.openUri(this, websiteUrl)
    }

    private fun updateSubscriptionInfo() {
        val userEmail = MmkvManager.getUserEmail().orEmpty()
        if (userEmail.isNotBlank()) {
            binding.tvUserEmail.text = userEmail
            binding.tvUserEmail.isVisible = true
            binding.tvUserEmailClassic.text = userEmail
            binding.tvUserEmailClassic.isVisible = true
        } else {
            binding.tvUserEmail.isVisible = false
            binding.tvUserEmailClassic.isVisible = false
        }

        val info = MmkvManager.getSubscriptionInfo()
        val hasData = info != null && info.hasData()
        val isExpired = hasData && info!!.isExpired()

        // 1. Simple Mode: Card subscription info is always displayed on the dashboard card
        binding.cardSubInfo.isVisible = true

        if (!hasData) {
            // Case A: No subscription
            binding.tvSubTitle.text = getString(R.string.sub_title_empty)
            binding.tvSubPercent.isVisible = false

            binding.tvSubTraffic.text = getString(R.string.sub_traffic_empty)
            binding.tvSubTraffic.isVisible = true

            binding.pbSubTraffic.isVisible = true
            binding.pbSubTraffic.setIndicatorColor(ContextCompat.getColor(this, R.color.color_fab_active))
            binding.pbSubTraffic.progress = 0
            binding.tvSubReset.isVisible = false

            binding.tvSubExpire.text = getString(R.string.sub_expire_empty)
            binding.tvSubExpire.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant))
            binding.tvSubExpire.isVisible = true
        } else if (isExpired) {
            // Case B: Expired subscription
            val title = if (info!!.subName.isNotBlank()) info.subName else getString(R.string.app_name)
            binding.tvSubTitle.text = title
            binding.tvSubPercent.text = getString(R.string.sub_status_expired)
            binding.tvSubPercent.setTextColor(ContextCompat.getColor(this, R.color.colorPingRed))
            binding.tvSubPercent.isVisible = true

            val formattedTraffic = info.getFormattedTraffic()
            binding.tvSubTraffic.text = formattedTraffic.ifBlank { "0 B / 0 B" }
            binding.tvSubTraffic.isVisible = true

            binding.pbSubTraffic.isVisible = true
            binding.pbSubTraffic.setIndicatorColor(ContextCompat.getColor(this, R.color.colorPingRed))
            binding.pbSubTraffic.progress = 100

            binding.tvSubReset.isVisible = false

            val cleanExpireDate = info.getCleanExpireDate()
            binding.tvSubExpire.text = getString(R.string.sub_expire_format_with_status, cleanExpireDate, getString(R.string.sub_status_expired))
            binding.tvSubExpire.setTextColor(ContextCompat.getColor(this, R.color.colorPingRed))
            binding.tvSubExpire.isVisible = true
        } else {
            // Case C: Active valid subscription
            val title = if (info!!.subName.isNotBlank()) info.subName else getString(R.string.app_name)
            val formattedTraffic = info.getFormattedTraffic()
            val percent = info.calculateUsagePercent()
            val cleanResetDay = info.getCleanResetDay()
            val cleanExpireDate = info.getCleanExpireDate()

            binding.tvSubTitle.text = title
            if (formattedTraffic.isNotBlank()) {
                binding.tvSubTraffic.text = formattedTraffic
                binding.tvSubTraffic.isVisible = true
            } else {
                binding.tvSubTraffic.isVisible = false
            }

            if (percent >= 0) {
                binding.tvSubPercent.text = "${percent}%"
                binding.tvSubPercent.setTextColor(ContextCompat.getColor(this, R.color.color_fab_active))
                binding.tvSubPercent.isVisible = true
                binding.pbSubTraffic.isVisible = true
                binding.pbSubTraffic.setIndicatorColor(ContextCompat.getColor(this, R.color.color_fab_active))
                binding.pbSubTraffic.progress = percent
            } else {
                binding.tvSubPercent.isVisible = false
                binding.pbSubTraffic.isVisible = false
            }

            if (cleanResetDay.isNotBlank()) {
                binding.tvSubReset.text = getString(R.string.sub_reset_format, cleanResetDay)
                binding.tvSubReset.isVisible = true
            } else {
                binding.tvSubReset.isVisible = false
            }

            if (cleanExpireDate.isNotBlank()) {
                binding.tvSubExpire.text = getString(R.string.sub_expire_format, cleanExpireDate)
                binding.tvSubExpire.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant))
                binding.tvSubExpire.isVisible = true
            } else {
                binding.tvSubExpire.isVisible = false
            }
        }

        // 2. Classic Mode: Dashboard card is always displayed in classic mode
        binding.cardSubInfoClassic.isVisible = true

        if (!hasData) {
            // Case A: No subscription
            binding.tvSubTitleClassic.text = getString(R.string.sub_title_empty)
            binding.tvSubPercentClassic.isVisible = false

            binding.tvSubTrafficClassic.text = getString(R.string.sub_traffic_empty)
            binding.tvSubTrafficClassic.isVisible = true

            binding.pbSubTrafficClassic.isVisible = true
            binding.pbSubTrafficClassic.setIndicatorColor(ContextCompat.getColor(this, R.color.color_fab_active))
            binding.pbSubTrafficClassic.progress = 0
            binding.tvSubResetClassic.isVisible = false

            binding.tvSubExpireClassic.text = getString(R.string.sub_expire_empty)
            binding.tvSubExpireClassic.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant))
            binding.tvSubExpireClassic.isVisible = true
        } else if (isExpired) {
            // Case B: Expired subscription
            val title = if (info!!.subName.isNotBlank()) info.subName else getString(R.string.app_name)
            val cleanExpireDate = info.getCleanExpireDate()
            val formattedTraffic = info.getFormattedTraffic()

            binding.tvSubTitleClassic.text = title
            binding.tvSubPercentClassic.text = getString(R.string.sub_status_expired)
            binding.tvSubPercentClassic.setTextColor(ContextCompat.getColor(this, R.color.colorPingRed))
            binding.tvSubPercentClassic.isVisible = true

            binding.tvSubTrafficClassic.text = formattedTraffic.ifBlank { "0 B / 0 B" }
            binding.tvSubTrafficClassic.isVisible = true

            binding.pbSubTrafficClassic.isVisible = true
            binding.pbSubTrafficClassic.setIndicatorColor(ContextCompat.getColor(this, R.color.colorPingRed))
            binding.pbSubTrafficClassic.progress = 100

            binding.tvSubExpireClassic.text = getString(R.string.sub_expire_format_with_status, cleanExpireDate, getString(R.string.sub_status_expired))
            binding.tvSubExpireClassic.setTextColor(ContextCompat.getColor(this, R.color.colorPingRed))
            binding.tvSubExpireClassic.isVisible = true
            binding.tvSubResetClassic.isVisible = false
        } else {
            // Case C: Active valid subscription
            val title = if (info!!.subName.isNotBlank()) info.subName else getString(R.string.app_name)
            val formattedTraffic = info.getFormattedTraffic()
            val percent = info.calculateUsagePercent()
            val cleanResetDay = info.getCleanResetDay()
            val cleanExpireDate = info.getCleanExpireDate()

            binding.tvSubTitleClassic.text = title
            if (formattedTraffic.isNotBlank()) {
                binding.tvSubTrafficClassic.text = formattedTraffic
                binding.tvSubTrafficClassic.isVisible = true
            } else {
                binding.tvSubTrafficClassic.isVisible = false
            }

            if (percent >= 0) {
                binding.tvSubPercentClassic.text = "${percent}%"
                binding.tvSubPercentClassic.setTextColor(ContextCompat.getColor(this, R.color.color_fab_active))
                binding.tvSubPercentClassic.isVisible = true
                binding.pbSubTrafficClassic.isVisible = true
                binding.pbSubTrafficClassic.setIndicatorColor(ContextCompat.getColor(this, R.color.color_fab_active))
                binding.pbSubTrafficClassic.progress = percent
            } else {
                binding.tvSubPercentClassic.isVisible = false
                binding.pbSubTrafficClassic.isVisible = false
            }

            if (cleanResetDay.isNotBlank()) {
                binding.tvSubResetClassic.text = getString(R.string.sub_reset_format, cleanResetDay)
                binding.tvSubResetClassic.isVisible = true
            } else {
                binding.tvSubResetClassic.isVisible = false
            }

            if (cleanExpireDate.isNotBlank()) {
                binding.tvSubExpireClassic.text = getString(R.string.sub_expire_format, cleanExpireDate)
                binding.tvSubExpireClassic.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant))
                binding.tvSubExpireClassic.isVisible = true
            } else {
                binding.tvSubExpireClassic.isVisible = false
            }
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
        DialogUtil.showConfirmDialog(
            context = this,
            iconRes = R.drawable.ic_logout_24dp,
            titleRes = R.string.dialog_logout_title,
            messageRes = R.string.dialog_logout_message,
            confirmTextRes = R.string.menu_logout
        ) {
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
        DialogUtil.showConfirmDialog(
            context = this,
            iconRes = R.drawable.ic_delete_24dp,
            title = getString(R.string.title_del_all_config),
            message = getString(R.string.del_config_comfirm),
            confirmText = getString(R.string.menu_item_del_config)
        ) {
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
    }

    private fun delDuplicateConfig() {
        DialogUtil.showConfirmDialog(
            context = this,
            iconRes = R.drawable.ic_delete_24dp,
            title = getString(R.string.title_del_duplicate_config),
            message = getString(R.string.del_config_comfirm),
            confirmText = getString(R.string.menu_item_del_config)
        ) {
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
    }

    private fun delInvalidConfig() {
        DialogUtil.showConfirmDialog(
            context = this,
            iconRes = R.drawable.ic_delete_24dp,
            title = getString(R.string.title_del_invalid_config),
            message = getString(R.string.del_invalid_config_comfirm),
            confirmText = getString(R.string.menu_item_del_config)
        ) {
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
