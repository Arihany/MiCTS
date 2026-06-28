package com.parallelc.micts.ui.activity

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(Intent.ACTION_MAIN).setClass(this, MainActivity::class.java))
        finish()
        overridePendingTransition(0, 0)
    }
}
