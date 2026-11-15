package com.logscope.ai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern

object LogParser {
    private val threadtime = Pattern.compile("^\\s*(\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+)\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEFA])\\s+([^:]+):\\s?(.*)$")
    fun parse(raw: String): LogEntity? {
        val m = threadtime.matcher(raw)
        if (!m.find()) return null
        val level = when (m.group(4)) { "E", "F", "A" -> "ERROR"; "W" -> "WARN"; "I" -> "INFO"; "D" -> "DEBUG"; else -> "VERBOSE" }
        val date = m.group(1)
        val millis = try {
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
            val parsed = fmt.parse(date)
            if (parsed == null) System.currentTimeMillis() else {
                val parsedCalendar = Calendar.getInstance().apply { time = parsed }
                val now = Calendar.getInstance()
                parsedCalendar.set(Calendar.YEAR, now.get(Calendar.YEAR))
                parsedCalendar.timeInMillis
            }
        } catch (_: Exception) { System.currentTimeMillis() }
        val tag = m.group(5).trim()
        val message = m.group(6).trim()
        val category = classify(tag, message)
        return LogEntity(timestamp = millis, level = level, pid = m.group(2), tid = m.group(3), tag = tag, message = message, raw = raw, category = category)
    }
    private fun classify(tag: String, message: String): String {
        val s = "$tag $message".lowercase(Locale.ROOT)
        return when {
            "fatal exception" in s || "fatal signal" in s || "process has died" in s -> "CRASH"
            "not responding" in s || "am_anr" in s || "anr in " in s -> "ANR"
            "connectivity" in s || "dns" in s || "socket" in s || "ssl" in s || "network" in s -> "NETWORK"
            "battery" in s || "power" in s || "thermal" in s -> "POWER"
            "permission denial" in s || "securityexception" in s || "denied" in s -> "PERMISSION"
            "outofmemory" in s || "low memory" in s -> "MEMORY"
            else -> "SYSTEM"
        }
    }
}

data class Insight(val title: String, val severity: String, val explanation: String, val action: String)
object LocalAnalyzer {
    fun analyze(entries: List<LogEntity>): List<Insight> {
        if (entries.isEmpty()) return listOf(Insight("No logs available", "INFO", "Start a live capture or import a log session to analyze device events.", "Connect Shizuku and start collection."))
        val text = entries.joinToString("\n") { it.raw }.lowercase(Locale.ROOT)
        val out = mutableListOf<Insight>()
        fun addIf(needle: String, title: String, severity: String, explanation: String, action: String) { if (needle in text) out += Insight(title, severity, explanation, action) }
        addIf("fatal exception", "Application crash detected", "HIGH", "The log contains a fatal Java/Kotlin exception. The first exception line and following 'Caused by' chain usually identify the failure path.", "Open the matching timestamp in Log Explorer and inspect the full stack trace.")
        addIf("fatal signal", "Native process crash", "HIGH", "A native process reported a fatal signal. This can originate in JNI, native libraries, or memory access faults.", "Inspect tombstone references and the process name around the same timestamp.")
        addIf("anr in ", "Application not responding (ANR)", "HIGH", "Android reported an ANR. A main-thread stall, blocked I/O, lock contention, or heavy work may be involved.", "Correlate the ANR timestamp with ActivityManager and main-thread traces.")
        addIf("permission denial", "Permission access denied", "MEDIUM", "A component attempted an operation without the required permission or identity.", "Check the component, requested permission, and Android-version restrictions.")
        addIf("securityexception", "Security exception", "MEDIUM", "An operation was rejected by Android's security model.", "Verify runtime permissions, exported-component rules, and caller identity.")
        addIf("timed out", "Timeout observed", "MEDIUM", "At least one operation exceeded its expected response time.", "Compare nearby network, binder, and service events before changing configuration.")
        addIf("unknownhostexception", "DNS resolution failure", "MEDIUM", "A hostname could not be resolved at the time of the event.", "Check connectivity, DNS configuration, and whether the hostname is valid.")
        addIf("sslhandshakeexception", "TLS handshake failure", "MEDIUM", "A secure connection could not complete its TLS handshake.", "Check device date/time, certificate validity, and server TLS configuration.")
        addIf("outofmemoryerror", "Memory allocation failure", "HIGH", "The process could not allocate the requested memory.", "Inspect memory growth and large allocations; avoid assuming this proves a device-wide memory problem.")
        addIf("sqlite", "Database-related event", "LOW", "SQLite-related messages appear in the selected logs.", "Inspect the complete error code and transaction context before modifying data.")
        val errors = entries.count { it.level == "ERROR" }
        val warnings = entries.count { it.level == "WARN" }
        out += Insight("Capture summary", if (errors > 0) "MEDIUM" else "INFO", "$errors errors and $warnings warnings are present in the latest ${entries.size} stored entries. These counts describe the captured window only.", "Filter by ERROR, then inspect repeated tags and timestamps.")
        return out.distinctBy { it.title }.take(12)
    }
}
