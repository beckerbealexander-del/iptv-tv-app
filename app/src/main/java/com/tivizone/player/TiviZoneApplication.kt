package com.tivizone.player

import android.app.Application
import com.tivizone.player.util.CrashLogger

class TiviZoneApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLogger.init(this)
    }
}
