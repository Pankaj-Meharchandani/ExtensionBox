package com.extensionbox.app

import java.util.Locale

object Fmt {
    fun duration(ms: Long): String {
        var m_ms = if (ms < 0) 0 else ms
        val s = m_ms / 1000
        val m = s / 60
        val h = m / 60
        val d = h / 24
        return when {
            d > 0 -> String.format(Locale.US, "%dd %dh", d, h % 24)
            h > 0 -> String.format(Locale.US, "%dh %dm", h, m % 60)
            m > 0 -> String.format(Locale.US, "%dm %ds", m, s % 60)
            else -> String.format(Locale.US, "%ds", s)
        }
    }

    fun bytes(b: Long): String {
        val m_b = if (b < 0) 0 else b
        return when {
            m_b < 1024 -> "$m_b B"
            m_b < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", m_b / 1024.0)
            m_b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", m_b / (1024.0 * 1024))
            else -> String.format(Locale.US, "%.2f GB", m_b / (1024.0 * 1024 * 1024))
        }
    }

    fun speed(bytesPerSec: Long): String {
        val m_bps = if (bytesPerSec < 0) 0 else bytesPerSec
        return when {
            m_bps < 1024 -> "$m_bps B/s"
            m_bps < 1024 * 1024 -> String.format(Locale.US, "%.1f KB/s", m_bps / 1024.0)
            else -> String.format(Locale.US, "%.1f MB/s", m_bps / (1024.0 * 1024))
        }
    }

    fun percentage(p: Float): String = if (java.lang.Float.isNaN(p)) "—" else String.format(Locale.US, "%.1f%%", p)

    fun temp(celsius: Float): String = if (java.lang.Float.isNaN(celsius)) "—" else String.format(Locale.US, "%.1f°C", celsius)

    fun number(n: Long): String = String.format(Locale.US, "%,d", n)
}
