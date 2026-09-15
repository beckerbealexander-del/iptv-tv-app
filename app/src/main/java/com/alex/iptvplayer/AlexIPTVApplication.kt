package com.alex.iptvplayer

import android.app.Application
import com.alex.iptvplayer.util.CrashLogger

class AlexIPTVApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLogger.init(this)
    }
}
