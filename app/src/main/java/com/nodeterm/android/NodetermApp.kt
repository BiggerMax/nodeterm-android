package com.nodeterm.android

import android.app.Application
import android.content.Context
import com.nodeterm.android.notify.NotificationHelper

class NodetermApp : Application() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        LocaleManager.applyDefault(LocaleManager.currentLanguage(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
    }
}
