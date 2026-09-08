package com.example.opendash.util

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Optional plain-text capture of [DebugLog] output to a file, for analyzing a ride
 * after the fact (e.g. correlating dash telemetry bytes against GPS speed) when no
 * debugger/USB connection is available while riding.
 *
 * Off by default; [start]/[stop] are driven by DashViewModel around the dash
 * connection lifecycle, gated by the rider's "Capture dash diagnostics" setting.
 * Files live under the app's own cache "exports" directory so the existing
 * FileProvider path config already covers sharing them.
 *
 * Storage is capped at [MAX_TOTAL_BYTES] across ALL capture files combined — a
 * single ride's worth of timestamped text lines runs a few MB at most, so this
 * comfortably covers many rides before anything is deleted, without ever coming
 * close to filling the phone.
 */
object RideLogFile {
    private const val DIR_NAME = "exports/dash_logs"
    private const val MAX_TOTAL_BYTES = 50L * 1024 * 1024 // 50 MB across all captures combined

    // SimpleDateFormat is NOT thread-safe, and DebugLog is called from several
    // dispatchers at once (DashSession on IO, DashViewModel's frame loop on
    // Default, and any other subsystem using DebugLog while capture is on) —
    // every access below (formatting, the stream itself) goes through [lock].
    private val lock = Any()
    private val timestampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileNameFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private var writer: FileOutputStream? = null

    val isActive: Boolean get() = synchronized(lock) { writer != null }

    fun start(context: Context) {
        synchronized(lock) {
            if (writer != null) return
            val dir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
            enforceCap(dir)
            val file = File(dir, "ride_${fileNameFmt.format(Date())}.log")
            runCatching {
                writer = FileOutputStream(file, true)
                writeLocked("RideLog", "I", "=== capture started: ${file.name} ===")
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            val w = writer ?: return
            runCatching { writeLocked("RideLog", "I", "=== capture stopped ===") }
            runCatching { w.close() }
            writer = null
        }
    }

    fun append(tag: String, level: String, message: String) {
        synchronized(lock) {
            if (writer == null) return
            writeLocked(tag, level, message)
        }
    }

    /** Caller must hold [lock] and have already checked [writer] is non-null. */
    private fun writeLocked(tag: String, level: String, message: String) {
        val line = "${timestampFmt.format(Date())} $level/$tag: $message\n"
        runCatching { writer?.write(line.toByteArray(Charsets.UTF_8)) }
    }

    fun latestFile(context: Context): File? {
        val dir = File(context.cacheDir, DIR_NAME)
        return dir.listFiles()?.maxByOrNull { it.lastModified() }
    }

    /** Delete oldest capture files until the folder is back under [MAX_TOTAL_BYTES]. */
    private fun enforceCap(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= MAX_TOTAL_BYTES) break
            total -= f.length()
            f.delete()
        }
    }
}
