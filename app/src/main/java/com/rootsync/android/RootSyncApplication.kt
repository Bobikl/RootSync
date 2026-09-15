package com.rootsync.android

import android.app.Application
import com.topjohnwu.superuser.Shell

class RootSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setContext(this)
                .setTimeout(ROOT_REQUEST_TIMEOUT_SECONDS)
        )
    }

    private companion object {
        const val ROOT_REQUEST_TIMEOUT_SECONDS = 45L
    }
}
