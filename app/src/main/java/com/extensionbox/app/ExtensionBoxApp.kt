package com.extensionbox.app

import android.app.Application

class ExtensionBoxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PrivacyTracker.init(this)
    }
}
