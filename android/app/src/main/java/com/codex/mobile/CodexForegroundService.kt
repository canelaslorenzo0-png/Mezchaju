package com.codex.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class CodexForegroundService : Service() {

    companion object {
        private const val TAG = "MezchajuFg"
        private const val CHANNEL_ID = "codex_running"
        private const val NOTIFICATION_ID = 1
        private const val UPDATE_NOTIFICATION_ID = 2
        private const val DASH_URL = "http://127.0.0.1:${CodexServerManager.DASHBOARD_PORT}"

        const val ACTION_TOGGLE_GATEWAY = "com.codex.mobile.fg.TOGGLE_GATEWAY"
        const val ACTION_OPEN_APP = "com.codex.mobile.fg.OPEN_APP"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var notifiedUpdates = ""
    private var gatewayOnline = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        startUpdatePolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_GATEWAY -> Thread { toggleGateway() }.start()
            ACTION_OPEN_APP -> {
                val launch = Intent(this, MainActivity::class.java).apply {
                    this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(launch)
            }
            else -> { /* periodic refresh service start */}
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private fun startUpdatePolling() {
        val runnable = object : Runnable {
            override fun run() {
                pollHealth()
                handler.postDelayed(this, 15 * 60 * 1000L)
            }
        }
        handler.post(runnable)
    }

    private fun pollHealth() {
        Thread {
            try {
                val conn = URL("$DASH_URL/health").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                if (conn.responseCode !in 200..399) return@Thread
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                val services = json.optJSONObject("services") ?: return@Thread
                val gw = services.optJSONObject("openclaw-gateway")
                val online = gw?.optString("status", "") == "online"
                if (online != gatewayOnline) {
                    gatewayOnline = online
                    runOnUiThread { notifyUpdate(services, json.optJSONObject("updates")) }
                } else {
                    notifyUpdate(services, json.optJSONObject("updates"))
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun notifyUpdate(services: JSONObject, updates: JSONObject?) {
        val available = updates?.let { u ->
            u.keys().asSequence().filter { key ->
                u.optJSONObject(key)?.optBoolean("available", false) == true
            }.toList()
        } ?: emptyList()
        gatewayOnline = services.optJSONObject("openclaw-gateway")?.optString("status", "") == "online"
        val mgr = getSystemService(NotificationManager::class.java)
        mgr?.notify(NOTIFICATION_ID, buildNotification(gatewayOnline))

        val key = available.sorted().joinToString(",")
        if (key.isNotEmpty() && key != notifiedUpdates) {
            notifiedUpdates = key
            val label = available.joinToString(", ")
            val content = PendingIntent.getActivity(
                this, 3,
                Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            mgr?.notify(
                UPDATE_NOTIFICATION_ID,
                builder
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Updates available")
                    .setContentText("$label — update in the app dashboard")
                    .setContentIntent(content)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    private fun toggleGateway() {
        var action = if (gatewayOnline) "stop" else "start"
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
            val ok = conn.responseCode in 200..399
            conn.disconnect()
            if (ok) action = if (action == "stop") "stopped" else "started" else action = "toggle failed"
        } catch (e: Exception) {
            Log.w(TAG, "toggle failed: ${e.message}")
            action = "unreachable — open app"
        }
        runOnUiThread {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr?.notify(NOTIFICATION_ID, buildNotification(gatewayOnline))
        }
        pollHealth()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mezchaju Running",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps Mezchaju agents running in the background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(gatewayOnline: Boolean = false): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val togglePi = PendingIntent.getService(
            this, 1,
            Intent(this, CodexForegroundService::class.java).setAction(ACTION_TOGGLE_GATEWAY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val terminalPi = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                action = MainActivity.ACTION_OPEN_TERMINAL
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val status = if (gatewayOnline) "Gateway online" else "Gateway offline"
        return builder
            .setContentTitle("Mezchaju is running")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(0, if (gatewayOnline) "■ Stop Gateway" else "▶ Start Gateway", togglePi)
            .addAction(0, "⌨ Terminal", terminalPi)
            .build()
    }

    private fun runOnUiThread(block: () -> Unit) {
        handler.post(block)
    }
}
