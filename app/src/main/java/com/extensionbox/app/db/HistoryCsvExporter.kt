package com.extensionbox.app.db

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Streams a module's stored history to a CSV file chosen through the system file picker. */
object HistoryCsvExporter {

    private const val PAGE_SIZE = 2000
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val NUMERIC_LIKE = Regex("""^[+-]?\d[\d.,]*(\s.*)?$""")

    /**
     * Writes every stored row of [moduleKey] newer than [afterMs] (full resolution, no downsampling).
     * Returns the number of data rows written.
     */
    suspend fun export(context: Context, uri: Uri, moduleKey: String, afterMs: Long): Int =
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(context).moduleDataDao()

            // Pass 1: optional fields can appear mid-history, so gather the full column set before writing
            val columns = LinkedHashSet<String>()
            forEachPage(dao, moduleKey, afterMs) { page ->
                page.forEach { columns.addAll(it.data.keys) }
            }

            var rows = 0
            val out = context.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot open $uri")
            out.bufferedWriter(Charsets.UTF_8).use { w ->
                w.write("timestamp_ms,time")
                columns.forEach { w.write(","); w.write(escape(it)) }
                w.write("\r\n")

                // Pass 2: stream the rows
                forEachPage(dao, moduleKey, afterMs) { page ->
                    for (e in page) {
                        w.write(e.timestamp.toString())
                        w.write(",")
                        w.write(TIME_FORMAT.format(Instant.ofEpochMilli(e.timestamp).atZone(ZoneId.systemDefault())))
                        for (c in columns) {
                            w.write(",")
                            w.write(escape(e.data[c] ?: ""))
                        }
                        w.write("\r\n")
                        rows++
                    }
                }
            }
            rows
        }

    /** Keyset pagination on the primary key, so memory stays bounded however much history exists. */
    private suspend fun forEachPage(
        dao: ModuleDataDao,
        key: String,
        after: Long,
        block: (List<ModuleDataEntity>) -> Unit
    ) {
        var lastId = 0L
        while (true) {
            val page = dao.getHistoryPage(key, after, lastId, PAGE_SIZE)
            if (page.isEmpty()) return
            block(page)
            if (page.size < PAGE_SIZE) return
            lastId = page.last().id
        }
    }

    private fun escape(raw: String): String {
        // Spreadsheet apps treat cells starting with = @ + - as formulas; neutralise anything that isn't a plain number
        val safe = if (raw.isNotEmpty() && raw[0] in "=@+-\t\r" && !NUMERIC_LIKE.matches(raw)) "'$raw" else raw
        val needsQuotes = safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}
