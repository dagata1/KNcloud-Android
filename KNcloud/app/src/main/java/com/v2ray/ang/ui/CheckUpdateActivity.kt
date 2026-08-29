package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle

class CheckUpdateActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, UserAssetActivity::class.java))
        finish()
    }
}
