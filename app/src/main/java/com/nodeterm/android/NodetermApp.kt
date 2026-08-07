package com.nodeterm.android

import android.app.Application
import com.nodeterm.android.notify.NotificationHelper

class NodetermApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
    }
}
