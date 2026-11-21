package com.ohyooo.lvtu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*

class DownloadProgressReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT < 34 || sentFromPackage != DownloadNotifications.HOST) return
        val state = intent.extras ?: return
        val pending = goAsync()
        scope.launch {
            try {
                DownloadNotifications.receive(context, intent.action == "com.ohyooo.lvtu.CANCEL_NOTIFICATION", state)
            } catch (error: Exception) {
                Log.e("LvTu", "下载通知发送失败", error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    }
}
