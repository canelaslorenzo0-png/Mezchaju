package com.codex.mobile

import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Native per-service log viewer: tails ~/.mezchaju/logs/<service>.log from
 * the dashboard server, with an inline filter box.
 */
class NativeLogViewer(private val activity: MainActivity) {

    private val handler = Handler(Looper.getMainLooper())
    private var service: String? = null
    private var polling = false

    val overlay: FrameLayout = FrameLayout(activity).apply {
        setBackgroundColor(0xF005070D.toInt())
        setVisibility(View.GONE)
    }

    private val logText: TextView = TextView(activity).apply {
        setTextColor(0xFFB9F5D3.toInt())
        textSize = 12f
        typeface = Typeface.MONOSPACE
        setLineSpacing(0f, 1.05f)
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    private val scroll: ScrollView = ScrollView(activity).apply {
        isVerticalScrollBarEnabled = true
        addView(logText)
    }

    private val filterInput: EditText = EditText(activity).apply {
        hint = "filter…"
        inputType = InputType.TYPE_CLASS_TEXT
        setTextColor(0xFFE8EDF7.toInt())
        setHintTextColor(0xFF5A6880.toInt())
        setBackgroundColor(0x1FFFFFFF.toInt())
        setPadding(dp(10), dp(6), dp(10), dp(6))
        textSize = 13f
    }

    init {
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xE6121A2B.toInt())
            setPadding(dp(14), dp(10), dp(12), dp(10))
        }
        header.addView(
            TextView(activity).apply {
                text = "Logs"
                setTextColor(0xFFEAF0FF.toInt())
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        header.addView(
            filterInput,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f),
        )
        header.addView(
            Button(activity).apply {
                text = "CLOSE"
                textSize = 12f
                isAllCaps = false
                setTextColor(0xFFFFD9C7.toInt())
                setBackgroundColor(0x33FF7849.toInt())
                setPadding(dp(14), dp(6), dp(14), dp(6))
                setOnClickListener { hide() }
            },
        )

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        )
        overlay.addView(header, params)
        overlay.addView(scroll)
    }

    fun show(serviceName: String) {
        service = serviceName
        filterInput.setText("")
        logText.text = "loading…"
        overlay.visibility = View.VISIBLE
        startPolling()
    }

    fun hide() {
        stopPolling()
        overlay.visibility = View.GONE
        service = null
    }

    fun isShowing(): Boolean = overlay.visibility == View.VISIBLE

    private fun startPolling() {
        if (polling) return
        polling = true
        poll()
        handler.post(pollRunnable)
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(pollRunnable)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            poll()
            handler.postDelayed(this, 2000)
        }
    }

    private fun poll() {
        val svc = service ?: return
        Thread {
            try {
                val conn = URL("http://127.0.0.1:${CodexServerManager.DASHBOARD_PORT}/logs?service=$svc&tail=400")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 2500
                conn.readTimeout = 2500
                val lines = if (conn.responseCode in 200..399) {
                    JSONObject(conn.inputStream.bufferedReader().readText())
                        .optJSONArray("lines")?.let { arr ->
                            (0 until arr.length()).map { arr.optString(it) }
                        } ?: emptyList()
                } else {
                    emptyList()
                }
                conn.disconnect()
                activity.runOnUiThread { render(lines) }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun render(all: List<String>) {
        if (overlay.visibility != View.VISIBLE) return
        val filter = filterInput.text.toString().trim()
        val lines = if (filter.isEmpty()) all else all.filter { it.contains(filter, ignoreCase = true) }
        val text = lines.takeLast(250).joinToString("\n").ifEmpty { "— no matching log lines —" }
        logText.text = text
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
}
