package com.extensionbox.app.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.extensionbox.app.MonitorService
import com.extensionbox.app.Prefs
import com.extensionbox.app.SystemAccess
import com.extensionbox.app.db.AppDatabase
import com.extensionbox.app.db.ModuleDataEntity
import com.extensionbox.app.ui.ModuleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val database = AppDatabase.getDatabase(context)

    val isRunning: StateFlow<Boolean> = Prefs.getBoolFlow(context, "running", false)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Prefs.isRunning(context))

    private val _dashData = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val dashData: StateFlow<Map<String, Map<String, String>>> = _dashData.asStateFlow()

    private val _historyData = MutableStateFlow<Map<String, List<ModuleDataEntity>>>(emptyMap())
    val historyData: StateFlow<Map<String, List<ModuleDataEntity>>> = _historyData.asStateFlow()

    private val _sysAccess = MutableStateFlow<SystemAccess?>(null)
    val sysAccess: StateFlow<SystemAccess?> = _sysAccess.asStateFlow()

    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    private val _visibleModules = MutableStateFlow<List<com.extensionbox.app.modules.Module>>(emptyList())
    val visibleModules: StateFlow<List<com.extensionbox.app.modules.Module>> = _visibleModules.asStateFlow()

    private val expandedStates = mutableStateMapOf<String, Boolean>()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _sysAccess.value = SystemAccess(context)
        }
        observeData()
    }

    fun updateVisibleModules() {
        val all = ModuleRegistry.getModules()
        val enabled = all.filter { Prefs.isModuleEnabled(context, it.key(), it.defaultEnabled()) }
        val savedOrderStr = Prefs.getString(context, "dash_module_order", null)
        val sorted = if (!savedOrderStr.isNullOrBlank()) {
            val orderList = savedOrderStr.split(",").map { it.trim() }
            enabled.sortedBy { m ->
                val idx = orderList.indexOf(m.key())
                if (idx >= 0) idx else 1000 - m.priority()
            }
        } else {
            enabled.sortedByDescending { it.priority() }
        }
        _visibleModules.value = sorted
    }

    fun updateOrder(fromIndex: Int, toIndex: Int) {
        val current = _visibleModules.value.toMutableList()
        if (fromIndex in current.indices && toIndex in current.indices) {
            val item = current.removeAt(fromIndex)
            current.add(toIndex, item)
            _visibleModules.value = current
            val newOrderKeys = current.map { it.key() }
            Prefs.setString(context, "dash_module_order", newOrderKeys.joinToString(","))
        }
    }

    fun updateOrderKeys(fromKey: String, toKey: String) {
        val current = _visibleModules.value.toMutableList()
        val fromIdx = current.indexOfFirst { it.key() == fromKey }
        val toIdx = current.indexOfFirst { it.key() == toKey }
        if (fromIdx >= 0 && toIdx >= 0) {
            val item = current.removeAt(fromIdx)
            current.add(toIdx, item)
            _visibleModules.value = current
            val newOrderKeys = current.map { it.key() }
            Prefs.setString(context, "dash_module_order", newOrderKeys.joinToString(","))
        }
    }

    private fun observeData() {
        viewModelScope.launch {
            while (true) {
                val enabledModules = ModuleRegistry.getModules().filter {
                    Prefs.isModuleEnabled(context, it.key(), it.defaultEnabled())
                }
                
                val activeKeys = MonitorService.getActiveModuleKeys()
                val dataMap = mutableMapOf<String, Map<String, String>>()
                val histMap = mutableMapOf<String, List<ModuleDataEntity>>()
                val fifteenMinsAgo = System.currentTimeMillis() - (15 * 60 * 1000)

                for (module in enabledModules) {
                    val latest = database.moduleDataDao().getLatest(module.key())
                    if (latest != null) {
                        dataMap[module.key()] = latest.data
                    } else {
                        val memData = com.extensionbox.app.MonitorService.getModuleData(module.key())
                        if (memData != null) {
                            dataMap[module.key()] = memData
                        }
                    }
                    val history = database.moduleDataDao().getHistoryList(module.key(), fifteenMinsAgo)
                    histMap[module.key()] = history
                }

                _activeCount.value = activeKeys.size
                _dashData.value = dataMap
                _historyData.value = histMap
                updateVisibleModules()
                
                delay(2000)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            updateVisibleModules()
            fetchDataOnce()
        }
    }

    private suspend fun fetchDataOnce() {
        val enabledModules = ModuleRegistry.getModules().filter {
            Prefs.isModuleEnabled(context, it.key(), it.defaultEnabled())
        }
        val activeKeys = MonitorService.getActiveModuleKeys()
        val dataMap = mutableMapOf<String, Map<String, String>>()
        val histMap = mutableMapOf<String, List<ModuleDataEntity>>()
        val fifteenMinsAgo = System.currentTimeMillis() - (15 * 60 * 1000)

        for (module in enabledModules) {
            val latest = database.moduleDataDao().getLatest(module.key())
            if (latest != null) {
                dataMap[module.key()] = latest.data
            } else {
                val memData = com.extensionbox.app.MonitorService.getModuleData(module.key())
                if (memData != null) {
                    dataMap[module.key()] = memData
                }
            }
            val history = database.moduleDataDao().getHistoryList(module.key(), fifteenMinsAgo)
            histMap[module.key()] = history
        }

        _activeCount.value = activeKeys.size
        _dashData.value = dataMap
        _historyData.value = histMap
    }

    /** Downsampled history for the detail chart: one sample per [bucketMs] over the last [durationMs]. */
    suspend fun loadHistory(key: String, durationMs: Long, bucketMs: Long): List<ModuleDataEntity> =
        withContext(Dispatchers.IO) {
            database.moduleDataDao().getHistoryBucketed(key, System.currentTimeMillis() - durationMs, bucketMs)
        }

    fun toggleExpansion(key: String) {
        expandedStates[key] = !(expandedStates[key] ?: false)
    }

    fun isExpanded(key: String): Boolean {
        return if (Prefs.getBool(context, "dash_expand_cards", true)) {
            expandedStates[key] ?: false
        } else {
            true
        }
    }
}
