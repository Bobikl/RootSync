package com.rootsync.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.rootsync.android.ui.RootSyncApp
import com.rootsync.android.ui.SyncViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: SyncViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RootSyncApp(viewModel)
        }
    }
}
