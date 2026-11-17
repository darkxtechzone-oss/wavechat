package com.darkx.wave

import android.app.Application
import android.content.Context
import java.io.File

class WaveApp : Application() {
    companion object {
        lateinit var ctx: Context

        /** True while the app UI is on screen. */
        @Volatile
        var visible = false
    }

    override fun onCreate() {
        super.onCreate()
        ctx = applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val trace = android.util.Log.getStackTraceString(e)
                File(filesDir, "crash.txt").writeText(trace)
                val ext = getExternalFilesDir(null)
                if (ext != null) File(ext, "last_crash.txt").writeText(trace)
            } catch (x: Throwable) {
            }
            if (prev != null) prev.uncaughtException(t, e)
        }
    }
}
