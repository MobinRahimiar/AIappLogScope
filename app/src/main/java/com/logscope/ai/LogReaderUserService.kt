package com.logscope.ai

import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader

/** Runs as the Shizuku shell identity. Only fixed, read-only diagnostic commands are executed. */
class LogReaderUserService : ILogScopeService.Stub() {
    @Volatile private var process: Process? = null
    @Volatile private var reader: BufferedReader? = null

    @Synchronized override fun startLogcat() {
        if (process?.isAlive == true) return
        stopInternal()
        // No user-provided command or shell interpolation is accepted here.
        val p = Runtime.getRuntime().exec(arrayOf("/system/bin/logcat", "-b", "all", "-v", "threadtime"))
        process = p
        reader = BufferedReader(InputStreamReader(p.inputStream))
    }

    @Synchronized override fun readAvailable(maxLines: Int): Array<String> {
        val r = reader ?: return emptyArray()
        val out = ArrayList<String>(maxLines.coerceIn(1, 500))
        val limit = maxLines.coerceIn(1, 500)
        try {
            while (out.size < limit && r.ready()) {
                val line = r.readLine() ?: break
                if (line.isNotBlank()) out.add(line)
            }
        } catch (_: Exception) { }
        return out.toTypedArray()
    }

    override fun getDeviceSummary(): String {
        return try {
            val model = System.getProperty("ro.product.model") ?: "Android device"
            "Log reader running · $model · uptime ${SystemClock.elapsedRealtime() / 1000}s"
        } catch (_: Exception) { "Log reader running" }
    }

    @Synchronized override fun stopLogcat() = stopInternal()

    private fun stopInternal() {
        try { reader?.close() } catch (_: Exception) { }
        try { process?.destroy() } catch (_: Exception) { }
        reader = null
        process = null
    }

    override fun destroy() {
        stopInternal()
        System.exit(0)
    }
}
