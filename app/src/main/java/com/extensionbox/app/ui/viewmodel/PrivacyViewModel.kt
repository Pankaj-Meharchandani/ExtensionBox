package com.extensionbox.app.ui.viewmodel

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.extensionbox.app.SystemAccess
import com.extensionbox.app.ui.screens.AppCategory
import com.extensionbox.app.ui.screens.AppInfo
import com.extensionbox.app.ui.screens.PermissionInfo
import com.extensionbox.app.ui.screens.PrivacyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppTypeFilter {
    ALL, USER, SYSTEM, HIGH_RISK
}

class PrivacyViewModel : ViewModel() {

    private val _events = MutableStateFlow<List<PrivacyEvent>>(emptyList())
    val events: StateFlow<List<PrivacyEvent>> = _events.asStateFlow()

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private val _selectedAppPackage = MutableStateFlow<String?>(null)
    val selectedAppPackage: StateFlow<String?> = _selectedAppPackage.asStateFlow()

    private val _selectedAppPermissions = MutableStateFlow<List<PermissionInfo>>(emptyList())
    val selectedAppPermissions: StateFlow<List<PermissionInfo>> = _selectedAppPermissions.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _activeTab = MutableStateFlow(0) // 0: Timeline, 1: App Inspector, 2: Permission Matrix
    val activeTab: StateFlow<Int> = _activeTab.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _appFilter = MutableStateFlow(AppTypeFilter.ALL)
    val appFilter: StateFlow<AppTypeFilter> = _appFilter.asStateFlow()

    private val _matrixCategory = MutableStateFlow("CAMERA")
    val matrixCategory: StateFlow<String> = _matrixCategory.asStateFlow()

    fun setActiveTab(tab: Int) {
        _activeTab.value = tab
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setAppFilter(filter: AppTypeFilter) {
        _appFilter.value = filter
    }

    fun setMatrixCategory(category: String) {
        _matrixCategory.value = category
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    fun loadData(ctx: Context, sys: SystemAccess) {
        viewModelScope.launch {
            _isRefreshing.value = true
            withContext(Dispatchers.IO) {
                try {
                    val history = sys.getPrivacyHistory(ctx)
                    _events.value = history

                    val pm = ctx.packageManager
                    val installed = pm.getInstalledPackages(0)
                    val appList = installed.map { pkg ->
                        val appInfo = pkg.applicationInfo!!
                        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        
                        // Categorize risk based on requested permissions / package attributes
                        val category = when {
                            isSystem -> AppCategory.SAFE
                            pkg.packageName.contains("google") || pkg.packageName.contains("android") -> AppCategory.SAFE
                            else -> AppCategory.CAUTION
                        }

                        AppInfo(
                            packageName = pkg.packageName,
                            label = appInfo.loadLabel(pm).toString(),
                            isSystem = isSystem,
                            category = category
                        )
                    }.sortedBy { it.label }
                    
                    _apps.value = appList
                } catch (e: Exception) {
                    _toastMessage.value = "Failed to load privacy data: ${e.localizedMessage}"
                }
            }
            _isRefreshing.value = false
        }
    }

    fun selectApp(sys: SystemAccess, packageName: String?) {
        _selectedAppPackage.value = packageName
        if (packageName != null) {
            loadAppPermissions(sys, packageName)
        } else {
            _selectedAppPermissions.value = emptyList()
        }
    }

    fun loadAppPermissions(sys: SystemAccess, packageName: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                _selectedAppPermissions.value = sys.getAppPermissions(packageName)
            }
        }
    }

    fun updatePermission(sys: SystemAccess, packageName: String, opName: String, mode: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val success = sys.setAppPermission(packageName, opName, mode)
                if (success) {
                    _selectedAppPermissions.value = sys.getAppPermissions(packageName)
                    _toastMessage.value = "Updated $opName to $mode for ${packageName.substringAfterLast('.')}"
                } else {
                    _toastMessage.value = "Permission change requires Shizuku or Root access"
                }
            }
        }
    }
}
