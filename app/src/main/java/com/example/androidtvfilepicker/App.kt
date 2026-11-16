package com.example.androidtvfilepicker

import android.app.Application

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        EventLog.add(this, "App start")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        EventLog.add(this, "App onTrimMemory level=$level")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        EventLog.add(this, "App onLowMemory")
    }
}
