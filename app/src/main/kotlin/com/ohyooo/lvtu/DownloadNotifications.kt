package com.ohyooo.lvtu

import android.Manifest
import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle

class DownloadNotifications : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = context!!
        val packages = context.packageManager.getPackagesForUid(Binder.getCallingUid())
        if (packages == null || HOST !in packages) throw SecurityException("仅允许航旅纵横更新离线下载通知")
        return Bundle().apply {
            putBoolean("allowed", receive(context, method == "cancel", extras!!))
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        args: Array<out String>?,
        order: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        const val HOST = "com.umetrip.android.msky.app"
        const val PAUSE = "com.ohyooo.lvtu.PAUSE_DOWNLOAD"
        private const val CHANNEL = "offline_download"

        @Volatile
        private var pending: Bundle? = null
        const val MODULE = "com.ohyooo.lvtu"

        fun prepare(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "离线地图下载",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            manager.setNotificationDelegate(HOST)
        }

        fun postDelegated(context: Context, cancel: Boolean, state: Bundle): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.canNotifyAsPackage(MODULE)) return false
            if (cancel) manager.cancelAsPackage(MODULE, state.getString("token"), 1)
            else manager.notifyAsPackage(MODULE, state.getString("token"), 1, build(context, state))
            return true
        }

        fun receive(context: Context, cancel: Boolean, extras: Bundle): Boolean {
            if (cancel) {
                context.getSystemService(NotificationManager::class.java)
                    .cancel(extras.getString("token"), 1)
                if (extras.getString("token") == pending?.getString("token")) pending = null
                return true
            }
            val state = Bundle(extras)
            pending = state
            return show(context, state)
        }

        fun flush(context: Context) {
            pending?.let { show(context, it) }
        }

        private fun show(context: Context, state: Bundle): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "离线地图下载",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
            if (!manager.areNotificationsEnabled()) return false
            manager.notify(state.getString("token"), 1, build(context, state))
            return true
        }

        private fun build(context: Context, state: Bundle): Notification {
            val total = state.getInt("total")
            val done = state.getInt("done")
            val failed = state.getInt("failed")
            val finished = state.getBoolean("finished")
            val paused = state.getBoolean("paused")
            val token = state.getString("token")!!
            val launch = context.packageManager.getLaunchIntentForPackage(HOST)
            val title = when {
                finished && paused -> "离线地图已暂停"
                finished && failed > 0 -> "离线地图下载结束，有失败瓦片"
                finished -> "离线地图下载完成"
                failed > 0 -> "离线地图自动重试中"
                else -> "正在下载离线地图"
            }
            val percent = if (total == 0) 0 else done * 100 / total
            val retry = if (failed > 0) " · 待重试 $failed" else ""
            val builder = Notification.Builder(context, CHANNEL)
                .setSmallIcon(if (finished) R.drawable.stat_sys_download_done else R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText("$done / $total 张 · $percent%$retry")
                .setOnlyAlertOnce(true).setOngoing(!finished)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setProgress(if (finished) 0 else total, if (finished) 0 else done, false)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        0,
                        launch,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            if (!finished) {
                val pause = Intent(PAUSE).setPackage(HOST).putExtra("token", token)
                builder.addAction(
                    Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_media_pause), "暂停",
                        PendingIntent.getBroadcast(
                            context,
                            token.hashCode(),
                            pause,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    ).build()
                )
            }
            return builder.build()
        }
    }
}
