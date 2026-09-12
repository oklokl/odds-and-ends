package com.krdonon.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class IdleLogCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AppLog.handleIdleCleanup(context.applicationContext)
    }
}
