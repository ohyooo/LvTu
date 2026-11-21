package com.ohyooo.lvtu

import android.content.Context
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object LegacyStorage {
    fun migrate(context: Context) {
        val oldName = "hlzh-offline"
        val newName = "lvtu-offline"
        val oldDatabase = context.getDatabasePath("$oldName.db")
        val newDatabase = context.getDatabasePath("$newName.db")
        if (oldDatabase.isFile && !newDatabase.exists()) {
            // Move sidecars first so an interrupted migration can finish before opening the new DB
            for (suffix in listOf("-wal", "-shm", "-journal", "")) {
                val source = java.io.File(oldDatabase.path + suffix)
                if (source.exists()) Files.move(
                    source.toPath(),
                    java.io.File(newDatabase.path + suffix).toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        }
        val preferences = context.getSharedPreferences(newName, Context.MODE_PRIVATE)
        if (preferences.getBoolean("storage_migrated", false)) return
        val editor = preferences.edit()
        for ((key, value) in context.getSharedPreferences(oldName, Context.MODE_PRIVATE).all) {
            if (preferences.contains(key)) continue
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
            }
        }
        if (!editor.putBoolean("storage_migrated", true).commit()) error("旧下载参数迁移失败")
    }
}
