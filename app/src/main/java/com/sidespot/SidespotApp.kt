package com.sidespot

import android.app.Application
import com.sidespot.bridge.NativeBridge
import com.sidespot.offline.DownloadManager
import java.io.File

class SidespotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NativeBridge.init()
        NativeBridge.setTmpDir(cacheDir.absolutePath)
        NativeBridge.setOfflineDir(File(filesDir, "offline").absolutePath)
        DownloadManager.init(this)
    }
}
