package com.ohyooo.lvtu

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle

class NotificationPermissionActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        DownloadNotifications.prepare(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf<String>(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else {
            DownloadNotifications.flush(this)
            finish()
        }
    }

    override fun onRequestPermissionsResult(request: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(request, permissions, results)
        DownloadNotifications.flush(this)
        finish()
    }
}
