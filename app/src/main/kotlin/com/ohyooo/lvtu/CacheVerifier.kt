package com.ohyooo.lvtu

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.*

object CacheVerifier {
    private const val DAY = 86_400_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null
    private var installed = false

    fun install(application: Application) {
        if (installed || Application.getProcessName() != application.packageName) return
        installed = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (eligible(context)) start(context) else job?.cancel()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        application.registerReceiver(
            receiver,
            filter,
            if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0
        )
        scope.launch {
            while (isActive) {
                if (eligible(application)) start(application)
                delay(3_600_000)
            }
        }
    }

    private fun eligible(context: Context): Boolean =
        context.getSystemService(BatteryManager::class.java).isCharging

    @Synchronized
    private fun start(context: Context) {
        if (job?.isActive == true) return
        job = scope.launch {
            val prefs = context.getSharedPreferences("lvtu-offline", 0)
            val now = System.currentTimeMillis()
            val attempted = prefs.getLong("verify_attempt", 0)
            val completed = prefs.getLong("verify_complete", 0)
            val resuming = prefs.getLong("verify_cursor", 0) > 0 && attempted > completed
            if (!eligible(context) || !resuming && now - maxOf(attempted, completed) < DAY) return@launch
            // Resume the same scan after interruption; only a new full scan consumes the daily interval.
            if (!resuming && !prefs.edit().putLong("verify_attempt", now).commit()) return@launch
            try {
                val file = context.getDatabasePath("lvtu-offline.db")
                if (!file.isFile) {
                    prefs.edit().putString("verify_status", "缓存文件不存在").apply()
                    return@launch
                }
                var cursorRow = prefs.getLong("verify_cursor", 0)
                var scanned = prefs.getLong("verify_scanned", 0)
                var invalid = prefs.getLong("verify_invalid", 0)
                val workContext = currentCoroutineContext()
                lowPriority { SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY) }.use { db ->
                    while (true) {
                        ensureActive()
                        if (!eligible(context)) throw CancellationException("停止核验：已拔电")
                        var batch = 0
                        lowPriority {
                            db.rawQuery(
                                "SELECT rowid,z,x,y,data FROM tiles WHERE rowid>? ORDER BY rowid LIMIT 8",
                                arrayOf(cursorRow.toString())
                            ).use { rows ->
                                while (rows.moveToNext()) {
                                    workContext.ensureActive()
                                    if (!eligible(context)) throw CancellationException("停止核验：已拔电")
                                    val z = rows.getInt(1)
                                    val bytes = rows.getBlob(4)
                                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                                    var valid = z in 0..18 && rows.getInt(2) in 0 until (1 shl z) &&
                                            rows.getInt(3) in 0 until (1 shl z) && options.outWidth == 256 && options.outHeight == 256
                                    if (valid) {
                                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                        valid = bitmap != null
                                        bitmap?.recycle()
                                    }
                                    if (!valid) invalid++
                                    scanned++
                                    batch++
                                    cursorRow = rows.getLong(0)
                                }
                            }
                        }
                        prefs.edit().putLong("verify_cursor", cursorRow).putLong("verify_scanned", scanned)
                            .putLong("verify_invalid", invalid).apply()
                        if (batch == 0) break
                        delay(2_000)
                    }
                }
                prefs.edit().putLong("verify_complete", System.currentTimeMillis())
                    .putLong("verify_cursor", 0).putLong("verify_scanned", 0).putLong("verify_invalid", 0)
                    .putString("verify_status", "已核验 $scanned 张，异常 $invalid 张").apply()
                Log.i("LvTu", "Cache verification completed checked=$scanned invalid=$invalid")
            } catch (cancelled: CancellationException) {
                prefs.edit().putString("verify_status", "核验已暂停，下次符合条件时继续").apply()
                throw cancelled
            } catch (error: Exception) {
                prefs.edit().putString("verify_status", "核验失败：${error.message}").apply()
                Log.e("LvTu", "Cache verification failed", error)
            }
        }
    }

    private suspend fun <T> lowPriority(work: () -> T): T = runInterruptible(Dispatchers.IO) {
        val previous = Process.getThreadPriority(Process.myTid())
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
            work()
        } finally {
            Process.setThreadPriority(previous)
        }
    }
}
