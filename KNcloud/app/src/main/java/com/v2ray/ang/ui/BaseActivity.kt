package com.v2ray.ang.ui

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.Language
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.CustomDividerItemDecoration
import com.v2ray.ang.util.MyContextWrapper
import com.v2ray.ang.util.Utils
import java.util.Locale

abstract class BaseActivity : AppCompatActivity() {
    protected var currentLanguageCode: String? = null
    protected var currentLocale: Locale? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        currentLanguageCode = MmkvManager.decodeSettingsString(AppConfig.PREF_LANGUAGE) ?: Language.AUTO.code
        currentLocale = SettingsManager.getLocale()
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (!Utils.getDarkModeStatus(this)) {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = true
            }
        }
    }

    /**
     * 平板 / 横屏等宽屏下内容区的最大宽度（dp），超出部分两侧留白居中，避免界面被横向拉伸。
     * 返回 0 表示不限制。
     */
    protected open val maxContentWidthDp: Int = 840

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        val content = findViewById<View>(android.R.id.content) ?: return
        if (maxContentWidthDp <= 0) return
        content.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            applyCenteredContentPadding(v, right - left)
        }
        applyCenteredContentPadding(content, content.width)
    }

    private fun applyCenteredContentPadding(content: View, width: Int) {
        if (width <= 0) return
        val maxPx = (maxContentWidthDp * resources.displayMetrics.density).toInt()
        val side = ((width - maxPx) / 2).coerceAtLeast(0)
        if (content.paddingLeft != side || content.paddingRight != side) {
            content.post { content.setPadding(side, content.paddingTop, side, content.paddingBottom) }
        }
    }

    override fun onResume() {
        super.onResume()
        val latestLanguageCode = MmkvManager.decodeSettingsString(AppConfig.PREF_LANGUAGE) ?: Language.AUTO.code
        val latestLocale = SettingsManager.getLocale()
        if ((currentLanguageCode != null && currentLanguageCode != latestLanguageCode) ||
            (currentLocale != null && currentLocale != latestLocale)) {
            recreate()
            return
        }
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        android.R.id.home -> {
            // Handles the home button press by delegating to the onBackPressedDispatcher.
            // This ensures consistent back navigation behavior.
            onBackPressedDispatcher.onBackPressed()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(MyContextWrapper.wrap(newBase ?: return, SettingsManager.getLocale()))
    }

    /**
     * Adds a custom divider to a RecyclerView.
     *
     * @param recyclerView  The target RecyclerView to which the divider will be added.
     * @param context       The context used to access resources.
     * @param drawableResId The resource ID of the drawable to be used as the divider.
     * @param orientation   The orientation of the divider (DividerItemDecoration.VERTICAL or DividerItemDecoration.HORIZONTAL).
     */
    fun addCustomDividerToRecyclerView(recyclerView: RecyclerView, context: Context?, drawableResId: Int, orientation: Int = DividerItemDecoration.VERTICAL) {
        // Get the drawable from resources
        val drawable = ContextCompat.getDrawable(context!!, drawableResId)
        requireNotNull(drawable) { "Drawable resource not found" }

        // Create a DividerItemDecoration with the specified orientation
        val dividerItemDecoration = CustomDividerItemDecoration(drawable, orientation)

        // Add the divider to the RecyclerView
        recyclerView.addItemDecoration(dividerItemDecoration)
    }
}
