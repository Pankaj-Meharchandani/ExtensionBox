package com.extensionbox.app.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.LinkedHashMap

// The composite index keeps per-module time-range queries from scanning the whole table.
// Keep it in sync with MIGRATION_1_2 in AppDatabase (Room validates the index name and columns).
@Entity(
    tableName = "module_data",
    indices = [Index(value = ["moduleKey", "timestamp"])]
)
data class ModuleDataEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val moduleKey: String,
    val data: LinkedHashMap<String, String>,
    val timestamp: Long = System.currentTimeMillis()
)
