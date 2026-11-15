package com.logscope.ai

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object CrashGuard {
    private const val PREFS = "crashguard"
    private const val KEY = "last_crash"
    private var installed = false

    /** اگر گزارش کرش قبلی وجود داشت، نمایشش می‌دهد و true برمی‌گرداند. */
    fun handle(activity: Activity): Boolean {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!installed) {
            installed = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                try { prefs.edit().putString(KEY, Log.getStackTraceString(e)).commit() } catch (_: Throwable) { }
                previous?.uncaughtException(t, e)
            }
        }
        val crash = prefs.getString(KEY, null) ?: return false
        show(activity, crash, prefs)
        return true
    }

    private fun show(activity: Activity, crash: String, prefs: android.content.SharedPreferences) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val title = TextView(activity).apply {
            text = "LogScope AI crashed last time. Copy this text and send it."
            setTextColor(Color.YELLOW); setPadding(pad, pad, pad, pad / 2)
        }
        val body = TextView(activity).apply {
            text = crash.take(6000)
            setTextColor(Color.WHITE); textSize = 11f
            setTextIsSelectable(true); setPadding(pad, 0, pad, pad)
        }
        val retry = Button(activity).apply {
            text = "Clear report and retry"
            setOnClickListener { prefs.edit().remove(KEY).commit(); activity.recreate() }
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(title); addView(retry); addView(body)
        }
        activity.setContentView(ScrollView(activity).apply {
            setBackgroundColor(Color.BLACK); addView(column)
        })
    }
}
