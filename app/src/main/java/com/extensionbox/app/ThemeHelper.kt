package com.extensionbox.app

import android.app.Activity

object ThemeHelper {
    const val MONET = 0
    const val GRUVBOX = 1
    const val CATPPUCCIN = 2
    const val NORD = 3
    const val AMOLED = 4
    const val SOLARIZED = 5
    const val DRACULA = 6
    const val DEFAULT = 7

    data class ThemeOption(val id: Int, val name: String)

    // Display order is intentionally independent from the persisted IDs so existing
    // users do not have their selected theme changed by a picker reorder.
    val OPTIONS = listOf(
        ThemeOption(DEFAULT, "Default"),
        ThemeOption(MONET, "Material You"),
        ThemeOption(AMOLED, "OLED"),
        ThemeOption(GRUVBOX, "Cyberpunk"),
        ThemeOption(CATPPUCCIN, "Catppuccin"),
        ThemeOption(NORD, "Matrix"),
        ThemeOption(SOLARIZED, "Sunset"),
        ThemeOption(DRACULA, "Dracula")
    )

    val NAMES = OPTIONS.map { it.name }.toTypedArray()

    fun nameForId(id: Int): String = OPTIONS.firstOrNull { it.id == id }?.name ?: "Default"

    fun idForName(name: String): Int = OPTIONS.firstOrNull { it.name == name }?.id ?: DEFAULT

    fun apply(activity: Activity) {
        val theme = Prefs.getInt(activity, "app_theme", DEFAULT)
        val resId = when (theme) {
            GRUVBOX -> R.style.AppTheme_Gruvbox
            CATPPUCCIN -> R.style.AppTheme_Catppuccin
            NORD -> R.style.AppTheme_Nord
            AMOLED -> R.style.AppTheme_Amoled
            SOLARIZED -> R.style.AppTheme_Solarized
            DRACULA -> R.style.AppTheme_Dracula
            else -> R.style.AppTheme
        }
        activity.setTheme(resId)
    }
}
