package com.financeos.smssync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Does not read the broadcast payload: it only schedules a sync from the inbox shortly after. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = LocalStore(context)
        if (store.isConnected && !store.pausedLocally) SyncRunner.enqueueNow(context, 30)
    }
}
