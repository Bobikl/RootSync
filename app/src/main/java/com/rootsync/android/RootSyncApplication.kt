package com.rootsync.android

import android.app.Application

// ROOT uses the same noninteractive su -c transport for checks and commands.
// Do not initialize a libsu interactive shell or impose its handshake timeout.
class RootSyncApplication : Application()