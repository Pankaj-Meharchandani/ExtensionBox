package com.extensionbox.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.extensionbox.app.Prefs
import com.extensionbox.app.ThemeHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import android.content.pm.PackageManager

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _themeIndex = MutableStateFlow(Prefs.getInt(context, "app_theme", ThemeHelper.DEFAULT))
    val themeIndex: StateFlow<Int> = _themeIndex.asStateFlow()

    private val _isShizukuRunning = MutableStateFlow(try { Shizuku.pingBinder() } catch (e: Exception) { false })
    val isShizukuRunning: StateFlow<Boolean> = _isShizukuRunning.asStateFlow()

    private val _shizukuPermissionGranted = MutableStateFlow(
        try { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
        catch (e: Exception) { false }
    )
    val shizukuPermissionGranted: StateFlow<Boolean> = _shizukuPermissionGranted.asStateFlow()

    fun updateTheme(index: Int) {
        Prefs.setInt(context, "app_theme", index)
        _themeIndex.value = index
    }

    fun refreshShizukuState() {
        val running = try { Shizuku.pingBinder() } catch (e: Exception) { false }
        _isShizukuRunning.value = running
        _shizukuPermissionGranted.value = try {
            running && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) { false }
    }

    fun setShizukuPermission(granted: Boolean) {
        _shizukuPermissionGranted.value = granted
        if (granted) _isShizukuRunning.value = true
    }
}
