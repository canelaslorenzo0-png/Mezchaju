package com.codex.mobile

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Home-screen widget: live gateway / Control UI / web UI status with a
 * one-tap gateway toggle and a quick way back into the app. Reads the
 * same /health + /api endpoints as the native dashboard; falls back to
 * opening the app when the dashboard is not reachable.
 */
class MezchajuWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE_GATEWAY = "com.codex.mobile.widget.TOGGLE_GATEWAY"
        const val ACTION_OPEN_APP = "com.codex.mobile.widget.OPEN_APP"
        private const val DASH_URL = "http://127.0.0.1:${CodexServerManager.DASHBOARD_PORT}"
        private const val PING = "…"

        fun parseStatus(json: JSONObject): String {
            val services = json.optJSONObject("services") ?: return "Dashboard offline"
            val parts = mutableListOf<String>()
            val order = listOf("openclaw-gateway" to "GW", "openclaw-control-ui" to "UI", "mezchaju-web" to "WEB")
            for ((key, label) in order) {
                val svc = services.optJSONObject(key) ?: continue
                val status = svc.optString("status", "")
                val dot = when (status) {
                    "online", "installed" -> "●"
                    else -> "○"
                }
                parts += "$label $dot"
            }
            return parts.joinToString("  ") { it }.ifEmpty { "Starting…" }
        }

        /** Update every placed widget with a fresh status string. */
        fun publish(context: Context, status: String) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, MezchajuWidget::class.java))
            if (ids.isEmpty()) return

            val rv = RemoteViews(context.packageName, R.layout.widget_mezchaju)
            val accent = try {
                Color.parseColor("#${BuildConfig.BRAND_ACCENT}")
            } catch (_: Exception) {
                Color.parseColor("#FF7849")
            }
            rv.setTextViewText(R.id.widgetStatus, status)
            rv.setTextColor(R.id.widgetStatus, Color.parseColor("#9AA7BD"))
            rv.setTextColor(R.id.widgetToggle, accent)

            val openPi = PendingIntent.getBroadcast(
                context, 1,
                Intent(context, MezchajuWidget::class.java).setAction(ACTION_OPEN_APP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val togglePi = PendingIntent.getBroadcast(
                context, 2,
                Intent(context, MezchajuWidget::class.java).setAction(ACTION_TOGGLE_GATEWAY),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            rv.setOnClickPendingIntent(R.id.widgetOpen, openPi)
            rv.setOnClickPendingIntent(R.id.widgetToggle, togglePi)
            mgr.updateAppWidget(ids, rv)
        }

        /** Refresh from the dashboard server asynchronously, then repaint. */
        fun refreshFromServer(context: Context) {
            Thread {
                var status = PING
                try {
                    val conn = URL("$DASH_URL/health").openConnection() as HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    if (conn.responseCode in 200..399) {
                        status = parseStatus(JSONObject(conn.inputStream.bufferedReader().readText()))
                    }
                    conn.disconnect()
                } catch (_: Exception) {
                    status = "Dashboard offline"
                } finally {
                    publish(context, status)
                }
            }.start()
        }
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        refreshFromServer(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_OPEN_APP -> context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            ACTION_TOGGLE_GATEWAY -> toggleGateway(context)
            else -> {
                // no-op; system widget broadcasts are handled by onUpdate
            }
        }
    }

    private fun toggleGateway(context: Context) {
        Thread {
            var action = "start"
            try {
                val conn = URL("$DASH_URL/health").openConnection() as HttpURLConnection
                conn.connectTimeout = 2500
                conn.readTimeout = 2500
                if (conn.responseCode in 200..399) {
                    val services = JSONObject(conn.inputStream.bufferedReader().readText())
                        .optJSONObject("services")
                    val gw = services?.optJSONObject("openclaw-gateway")
                    if (gw?.optString("status", "") == "online") action = "stop"
                }
                conn.disconnect()
            } catch (_: Exception) {
            }

            var posted = false
            try {
                val conn = URL("$DASH_URL/api").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 3000
                conn.readTimeout = 8000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use {
                    it.write(JSONObject().put("action", action).put("service", "openclaw-gateway").toString().toByteArray())
                }
                posted = conn.responseCode in 200..399
                conn.disconnect()
            } catch (_: Exception) {
                posted = false
            }

            val finalPosted = posted
            Handler(Looper.getMainLooper()).post {
                if (!finalPosted) {
                    context.startActivity(
                        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                Handler(Looper.getMainLooper()).postDelayed({ refreshFromServer(context) }, 1800)
            }
        }.start()
    }
}
