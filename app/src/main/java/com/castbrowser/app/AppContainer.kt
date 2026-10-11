package com.castbrowser.app

import android.content.Context
import com.castbrowser.app.browser.TabStateManager
import com.castbrowser.app.browser.WebViewPool
import com.castbrowser.app.cast.DlnaDiscoveryService
import com.castbrowser.app.data.AppDatabase
import com.castbrowser.app.data.AppRepository
import com.castbrowser.app.data.UserSettings
import com.castbrowser.app.detect.StreamProbeService

class AppContainer(context: Context) {
    val database: AppDatabase = AppDatabase.getInstance(context)
    val repository: AppRepository = AppRepository(database)
    val userSettings: UserSettings = UserSettings(context)
    val tabStateManager: TabStateManager = TabStateManager(context)
    val probeService: StreamProbeService = StreamProbeService()
    val webViewPool: WebViewPool = WebViewPool(context, tabStateManager, probeService)
    val dlnaDiscoveryService: DlnaDiscoveryService = DlnaDiscoveryService(context)
}
