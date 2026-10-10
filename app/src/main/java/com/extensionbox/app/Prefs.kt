package com.extensionbox.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ebox_settings",
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, "ebox"))
    }
)

object Prefs {

    // --- Synchronous Getters (Legacy bridge, uses runBlocking) ---
    // Note: In 2026 practice, we should migrate callers to observe flows or use suspended functions.
    // This bridge allows the app to function while we refactor.

    fun isModuleEnabled(c: Context, key: String, def: Boolean): Boolean = runBlocking {
        val prefKey = booleanPreferencesKey("m_${key}_enabled")
        c.dataStore.data.map { it[prefKey] ?: def }.first()
    }

    fun setModuleEnabled(c: Context, key: String, value: Boolean) = runBlocking {
        val prefKey = booleanPreferencesKey("m_${key}_enabled")
        c.dataStore.edit { it[prefKey] = value }
    }

    fun isRunning(c: Context): Boolean = runBlocking {
        val prefKey = booleanPreferencesKey("running")
        c.dataStore.data.map { it[prefKey] ?: false }.first()
    }

    fun setRunning(c: Context, value: Boolean) = runBlocking {
        val prefKey = booleanPreferencesKey("running")
        c.dataStore.edit { it[prefKey] = value }
    }

    fun getDataRetentionDays(c: Context): Int = runBlocking {
        val prefKey = intPreferencesKey("data_retention_days")
        c.dataStore.data.map { it[prefKey] ?: 7 }.first() // Default to 7 days
    }

    fun setDataRetentionDays(c: Context, value: Int) = runBlocking {
        val prefKey = intPreferencesKey("data_retention_days")
        c.dataStore.edit { it[prefKey] = value }
    }

    fun getInt(c: Context, key: String, def: Int): Int = runBlocking {
        c.dataStore.data.map { it.number(key)?.toInt() ?: def }.first()
    }

    fun getIntFlow(c: Context, key: String, def: Int) = 
        c.dataStore.data.map { it.number(key)?.toInt() ?: def }

    fun setInt(c: Context, key: String, value: Int) = runBlocking {
        val prefKey = intPreferencesKey(key)
        c.dataStore.edit { it[prefKey] = value }
    }

    fun getLong(c: Context, key: String, def: Long): Long = runBlocking {
        c.dataStore.data.map { it.number(key)?.toLong() ?: def }.first()
    }

    fun setLong(c: Context, key: String, value: Long) = runBlocking {
        val prefKey = longPreferencesKey(key)
        c.dataStore.edit { it[prefKey] = value }
    }

    fun getBool(c: Context, key: String, def: Boolean): Boolean = runBlocking {
        val prefKey = booleanPreferencesKey(key)
        c.dataStore.data.map { it[prefKey] ?: def }.first()
    }

    fun getBoolFlow(c: Context, key: String, def: Boolean) = 
        c.dataStore.data.map { it[booleanPreferencesKey(key)] ?: def }

    fun setBool(c: Context, key: String, value: Boolean) = runBlocking {
        val prefKey = booleanPreferencesKey(key)
        c.dataStore.edit { it[prefKey] = value }
    }

    fun getString(c: Context, key: String, def: String?): String? = runBlocking {
        val prefKey = stringPreferencesKey(key)
        c.dataStore.data.map { it[prefKey] ?: def }.first()
    }

    fun setString(c: Context, key: String, value: String?) = runBlocking {
        val prefKey = stringPreferencesKey(key)
        c.dataStore.edit { 
            if (value == null) it.remove(prefKey) else it[prefKey] = value
        }
    }

    fun isModuleVisibleInNotif(c: Context, key: String): Boolean = runBlocking {
        val prefKey = booleanPreferencesKey("notif_m_${key}_visible")
        c.dataStore.data.map { it[prefKey] ?: true }.first()
    }

    fun setModuleVisibleInNotif(c: Context, key: String, value: Boolean) = runBlocking {
        val prefKey = booleanPreferencesKey("notif_m_${key}_visible")
        c.dataStore.edit { it[prefKey] = value }
    }

    fun getAll(c: Context): Map<String, *> = runBlocking {
        c.dataStore.data.first().asMap().mapKeys { it.key.name }
    }

    fun clearAll(c: Context) = runBlocking {
        c.dataStore.edit { it.clear() }
    }

    fun resetDailyStats(c: Context) = runBlocking {
        c.dataStore.edit {
            it[intPreferencesKey("ulk_today")] = 0
            it[longPreferencesKey("stp_today")] = 0L
            it[longPreferencesKey("dat_daily_total")] = 0L
            it[longPreferencesKey("dat_daily_wifi")] = 0L
            it[longPreferencesKey("dat_daily_mobile")] = 0L
            it[longPreferencesKey("scr_on_acc")] = 0L
        }
    }

    // --- Numeric type tolerance ---

    /**
     * Reads a numeric value regardless of whether it is stored as Int or Long.
     * DataStore keys compare by name only, so a key written as Int (e.g. by an imported JSON file) and read
     * as Long used to throw ClassCastException. Reading the raw value avoids that.
     */
    @Suppress("UNCHECKED_CAST", "CAST_NEVER_SUCCEEDS")
    private fun Preferences.number(name: String): Number? =
        this[intPreferencesKey(name) as Preferences.Key<Any>] as? Number

    // --- Settings import ---

    // Runtime state that must never be restored from a file (importing running=true while the service is stopped)
    private val IMPORT_BLOCKED_KEYS = setOf("running")
    private const val IMPORT_MAX_KEYS = 1000
    private const val IMPORT_MAX_KEY_LEN = 128
    private const val IMPORT_MAX_STRING_LEN = 10_000
    private val IMPORT_RANGES = mapOf(
        "data_retention_days" to (1L..30L),
        "notif_refresh_ms" to (1000L..60000L)
    )

    /**
     * Imports settings from JSON produced by the export button.
     * Returns the number of values applied, or -1 if the text isn't valid JSON.
     * Nothing is written unless the whole file parses.
     */
    fun importJson(c: Context, json: String): Int = runBlocking {
        try {
            val obj = JSONObject(json)
            val writes = mutableListOf<(MutablePreferences, Map<String, Any>) -> Unit>()
            val names = obj.keys()
            while (names.hasNext() && writes.size < IMPORT_MAX_KEYS) {
                val key = names.next()
                if (key.isBlank() || key.length > IMPORT_MAX_KEY_LEN || key in IMPORT_BLOCKED_KEYS) continue
                when (val value = obj.get(key)) {
                    is Boolean -> writes.add { p, _ -> p[booleanPreferencesKey(key)] = value }
                    is Int, is Long -> {
                        val raw = (value as Number).toLong()
                        val num = IMPORT_RANGES[key]?.let { r -> raw.coerceIn(r.first, r.last) } ?: raw
                        val isLong = value is Long
                        writes.add { p, existing ->
                            // Keep the numeric type the key already has so readers stay consistent
                            if (isLong || existing[key] is Long) p[longPreferencesKey(key)] = num
                            else p[intPreferencesKey(key)] = num.toInt()
                        }
                    }
                    is Double -> writes.add { p, _ -> p[doublePreferencesKey(key)] = value }
                    is String -> if (value.length <= IMPORT_MAX_STRING_LEN) {
                        writes.add { p, _ -> p[stringPreferencesKey(key)] = value }
                    }
                    // JSON null, arrays and nested objects aren't valid preference values: skipped
                    else -> {}
                }
            }
            c.dataStore.edit { p ->
                val existing = p.asMap().mapKeys { it.key.name }
                writes.forEach { it(p, existing) }
            }
            writes.size
        } catch (e: Exception) {
            -1
        }
    }
}
