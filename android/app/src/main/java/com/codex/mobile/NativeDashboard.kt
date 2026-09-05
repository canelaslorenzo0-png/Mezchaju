package com.codex.mobile

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Native Android dashboard — real Kotlin views (no HTML). Shows every service
 * as a card with live status, start/stop/restart, one-tap Open UI and a
 * native Termux terminal per service, plus upstream update banners.
 */
class NativeDashboard(
    private val activity: MainActivity,
    private val serverManager: CodexServerManager,
) {
    companion object {
        private const val DASH_URL = "http://127.0.0.1:${CodexServerManager.DASHBOARD_PORT}"
        private val UPDATE_CMDS = mapOf(
            "openclaw-gateway" to "npm install -g openclaw@latest",
            "openclaw-control-ui" to "npm install -g openclaw@latest",
            "deepseek-harness" to "npm install -g @deepseek-ai/dsh@latest",
            "claw-code" to "cargo install --git https://github.com/ultraworkers/claw-code --root \"\$PREFIX\"",
        )

        // One-tap terminal commands per service (quick palette).
        private val QUICK_CMDS = mapOf(
            "openclaw-gateway" to listOf(
                "Status" to "openclaw gateway status",
                "Token list" to "openclaw gateway token list",
                "Version" to "openclaw --version",
                "Reset tokens" to "openclaw gateway token reset || true",
            ),
            "openclaw-control-ui" to listOf(
                "Version" to "openclaw --version",
                "UI assets" to "ls \"\$PREFIX/lib/node_modules/openclaw/dist/control-ui\" | head -20",
            ),
            "deepseek-harness" to listOf(
                "Version" to "dsh --version",
                "Help" to "dsh --help",
                "Doctor" to "dsh doctor || true",
            ),
            "claw-code" to listOf(
                "Version" to "claw --version",
                "Help" to "claw --help",
            ),
            "mezchaju-web" to listOf(
                "Bundle" to "ls \"\$PREFIX/lib/node_modules/codex-web-local\" | head -20",
                "Port" to "ss -ltnp 2>/dev/null | grep 18923 || echo \"not listening\"",
            ),
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    private var refreshBusy = false

    private data class Service(
        val name: String,
        val label: String,
        val icon: String,
        val kind: String,
        val port: Int,
        val ui: String,
        val description: String,
        val status: String,
        val version: String,
        val cli: String,
        val restarts: Int,
    )

    private data class Update(
        val installed: String,
        val latest: String,
        val available: Boolean,
    )

    private var services = emptyMap<String, Service>()
    private var updates = emptyMap<String, Update>()
    private var workspace = ""
    private var lastJson: JSONObject? = null

    // Brand accent from BuildConfig (overridden by branded builds).
    private val accent: Int = try {
        Color.parseColor("#${BuildConfig.BRAND_ACCENT}")
    } catch (_: Exception) {
        0xFFFF7849.toInt()
    }

    private fun accentA(alpha: Int): Int =
        Color.argb(alpha, Color.red(accent), Color.green(accent), Color.blue(accent))

    // ── UI roots ───────────────────────────────────────────────────────────

    private val container: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(28))
        setBackgroundColor(0xFF080B14.toInt())
    }

    private val bannerContainer: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
    }

    private val cardsContainer: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
    }

    private val statusLine: TextView

    val view: View = ScrollView(activity).apply {
        isVerticalScrollBarEnabled = false
        addView(container)
    }.also {
        statusLine = TextView(activity).apply {
            setTextColor(0xFF8FA0BF.toInt())
            textSize = 12.5f
            setPadding(0, 4, 0, 12)
        }
        container.addView(buildHeader())
        container.addView(bannerContainer)
        container.addView(statusLine)
        container.addView(cardsContainer)
    }

    // ── Header ─────────────────────────────────────────────────────────────

    private fun buildHeader(): View {
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(activity).apply {
            text = "🦞 ${BuildConfig.BRAND_NAME}"
            setTextColor(0xFFF1F5FF.toInt())
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        if (BuildConfig.DEBUG) {
            titleView.setOnLongClickListener {
                confirmResetAll()
                true
            }
        }
        titleRow.addView(titleView)
        val refreshBtn = pillButton("⟳ REFRESH", 0xFF22D3EE.toInt()) { refreshNow() }
        val webBtn = pillButton("WEB UI", 0xFFFF7849.toInt()) {
            activity.openWebUrl("$DASH_URL/")
        }
        titleRow.addView(refreshBtn)
        titleRow.addView(webBtn)
        col.addView(titleRow)

        col.addView(
            TextView(activity).apply {
                text = if (BuildConfig.LITE) {
                    "Lite · gateway + CLI agents · no web UI bundled"
                } else {
                    "Real Termux inside · one shared workspace · auto-updates"
                }
                setTextColor(0xFF9AA7BD.toInt())
                textSize = 12.5f
                setPadding(0, 6, 0, 0)
            },
        )

        val utilRow1 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        utilRow1.addView(pillButton("📁 FILES", 0x22FFFFFF.toInt()) { activity.openFiles() })
        utilRow1.addView(pillButton("🧩 SKILLS", 0x22FFFFFF.toInt()) { showSkillsDialog() })
        utilRow1.addView(pillButton("⚡ PROVIDERS", 0x22FFFFFF.toInt()) { testProvidersDialog() })
        col.addView(utilRow1)

        val utilRow2 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        utilRow2.addView(pillButton("💾 BACKUP", 0x2200FF9D.toInt()) { activity.backupAndShare() })
        utilRow2.addView(pillButton("♪ RESTORE", 0x2222D3EE.toInt()) { activity.pickBackupToRestore() })
        col.addView(utilRow2)
        return col
    }

    private fun pillButton(text: String, color: Int, onClick: () -> Unit): Button =
        Button(activity).apply {
            this.text = text
            textSize = 12f
            isAllCaps = false
            setTextColor(0xFFF1F5FF.toInt())
            background = rounded(0x22FFFFFF.toInt(), dp(12), 0x1FFFFFFF.toInt(), dp(1))
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) }
        }

    // ── Polling ────────────────────────────────────────────────────────────

    fun startPolling() {
        if (polling) return
        polling = true
        refreshNow()
        handler.post(pollRunnable)
    }

    fun stopPolling() {
        polling = false
        handler.removeCallbacks(pollRunnable)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            refreshNow()
            handler.postDelayed(this, 6000)
        }
    }

    fun refreshNow() {
        if (refreshBusy) return
        refreshBusy = true
        Thread {
            try {
                val conn = URL("$DASH_URL/health").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                val code = conn.responseCode
                if (code in 200..399) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    parse(json)
                }
                conn.disconnect()
            } catch (_: Exception) {
            } finally {
                refreshBusy = false
            }
        }.start()
    }

    private fun parse(json: JSONObject) {
        val svcObj = json.optJSONObject("services") ?: JSONObject()
        val updObj = json.optJSONObject("updates") ?: JSONObject()
        val parsed = LinkedHashMap<String, Service>()
        val keys = svcObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val s = svcObj.optJSONObject(key) ?: continue
            parsed[key] = Service(
                name = key,
                label = s.optString("name", key),
                icon = s.optString("icon", "📦"),
                kind = s.optString("kind", "server"),
                port = s.optInt("port", 0),
                ui = s.optString("ui", ""),
                description = s.optString("description", ""),
                status = s.optString("status", "offline"),
                version = s.optString("version", ""),
                cli = s.optString("cli", ""),
                restarts = s.optInt("restarts", 0),
            )
        }
        val parsedUpdates = LinkedHashMap<String, Update>()
        val ukeys = updObj.keys()
        while (ukeys.hasNext()) {
            val key = ukeys.next()
            val u = updObj.optJSONObject(key) ?: continue
            parsedUpdates[key] = Update(
                installed = u.optString("installed", ""),
                latest = u.optString("latest", ""),
                available = u.optBoolean("available", false),
            )
        }
        workspace = json.optString("workspace", "")
        services = parsed
        updates = parsedUpdates
        lastJson = json
        activity.runOnUiThread { render() }
    }

    // ── Render ─────────────────────────────────────────────────────────────

    private fun render() {
        val up = services.values.count { it.status == "online" || it.status == "installed" }
        val avail = updates.values.count { it.available }
        statusLine.text = "$up services ready · $avail update${if (avail == 1) "" else "s"} available · workspace: $workspace"

        renderBanners()
        renderCards()

        // Keep the home-screen widget in sync with live status.
        lastJson?.let { MezchajuWidget.publish(activity, MezchajuWidget.parseStatus(it)) }
    }

    private fun renderBanners() {
        bannerContainer.removeAllViews()
        for ((key, u) in updates) {
            if (!u.available) continue
            val label = services[key]?.label ?: key
            bannerContainer.addView(buildBanner(key, label, u))
        }
    }

    private fun buildBanner(key: String, label: String, u: Update): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(accentA(0x22), dp(14), accentA(0x55), dp(1))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        row.addView(
            TextView(activity).apply {
                text = "🚀"
                textSize = 20f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(10) }
            },
        )
        row.addView(
            TextView(activity).apply {
                text = "$label\n${u.installed} → ${u.latest}"
                setTextColor(0xFFFDEDE3.toInt())
                textSize = 12.5f
                setLineSpacing(0f, 1.05f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            Button(activity).apply {
                text = "UPDATE"
                textSize = 12f
                isAllCaps = false
                setTextColor(0xFF160F0A.toInt())
                background = rounded(accent, dp(12), Color.TRANSPARENT, 0)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                setOnClickListener { activity.openTerminalForService(key, MainActivity.NATIVE_UPDATE, UPDATE_CMDS[key]) }
            },
        )
        return row
    }

    private fun renderCards() {
        cardsContainer.removeAllViews()
        if (services.isEmpty()) {
            cardsContainer.addView(
                TextView(activity).apply {
                    text = "Connecting to the runtime…"
                    setTextColor(0xFF8FA0BF.toInt())
                    textSize = 13f
                    setPadding(0, dp(20), 0, 0)
                },
            )
            return
        }
        for ((key, svc) in services) {
            cardsContainer.addView(buildCard(key, svc))
        }
    }

    private fun buildCard(key: String, svc: Service): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(0xFF10162A.toInt(), dp(18), 0xFF1E2842.toInt(), dp(1))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }

        // header row: icon + title/desc + status pill
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            TextView(activity).apply {
                text = svc.icon
                textSize = 26f
                gravity = Gravity.CENTER
                setPadding(0, 0, dp(12), 0)
            },
        )
        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(
            TextView(activity).apply {
                text = svc.label
                setTextColor(0xFFF1F5FF.toInt())
                textSize = 15.5f
                typeface = Typeface.DEFAULT_BOLD
            },
        )
        titleCol.addView(
            TextView(activity).apply {
                text = svc.description
                setTextColor(0xFF9AA7BD.toInt())
                textSize = 11.5f
                setLineSpacing(0f, 1.1f)
            },
        )
        head.addView(titleCol)
        head.addView(statusPill(svc.status))
        card.addView(head)

        // chips: version + port/CLI + update chip
        val chips = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }
        chips.addView(chip(if (svc.kind == "server") "port ${svc.port}" else "CLI · ${svc.cli.ifBlank { "on-demand" }}"))
        if (svc.version.isNotBlank()) chips.addView(chip("v${svc.version}"))
        if (svc.restarts > 0) chips.addView(chip("↻ ${svc.restarts} crash restart${if (svc.restarts == 1) "" else "s"}", true))
        val up = updates[key]
        if (up != null && up.available) chips.addView(chip("↗ v${up.latest}", true))
        card.addView(chips)

        // actions
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }
        if (svc.kind == "server") {
            if (svc.ui.isNotBlank()) {
                actions.addView(actionButton("OPEN UI", 0x3322D3EE.toInt(), 0xFFA5F3FC.toInt()) {
                    activity.openWebUrl(svc.ui)
                })
            }
            val online = svc.status == "online"
            actions.addView(
                actionButton(
                    if (online) "■ STOP" else "▶ START",
                    if (online) 0x33F87171.toInt() else accent,
                    if (online) 0xFFFECACA.toInt() else 0xFF160F0A.toInt(),
                ) { doAction(key, if (online) "stop" else "start") },
            )
        } else {
            actions.addView(actionButton("▶ LAUNCH", accent, 0xFF160F0A.toInt()) {
                activity.openTerminalForService(key, MainActivity.NATIVE_LAUNCH, svc.cli)
            })
        }
        actions.addView(actionButton("TERMINAL", 0x22FFFFFF.toInt(), 0xFFF1F5FF.toInt()) {
            activity.openTerminalForService(key, MainActivity.NATIVE_TERMINAL)
        })
        card.addView(actions)

        // secondary row: logs + quick commands
        val sec = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        sec.addView(actionButton("LOGS", 0x22FFFFFF.toInt(), 0xFFA5F3FC.toInt()) {
            activity.openLogViewer(key)
        })
        sec.addView(actionButton("⌘ QUICK", 0x3322D3EE.toInt(), 0xFFA5F3FC.toInt()) {
            showQuickCommands(key, svc)
        })
        card.addView(sec)

        // update row
        if (up != null && up.available) {
            val urow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) }
            }
            urow.addView(
                actionButton("🚀 UPDATE → v${up.latest}", 0x3300FF9D.toInt(), 0xFFBBF7D0.toInt()) {
                    activity.openTerminalForService(key, MainActivity.NATIVE_UPDATE, UPDATE_CMDS[key])
                },
            )
            card.addView(urow)
        }
        return card
    }

    private fun statusPill(status: String): View {
        val (bg, fg) = when (status) {
            "online" -> 0x1F22C55E.toInt() to 0xFFBBF7D0.toInt()
            "installed" -> 0x1F22D3EE.toInt() to 0xFFA5F3FC.toInt()
            "offline" -> 0x26F87171.toInt() to 0xFFFECACA.toInt()
            else -> 0x1FFFFFFF.toInt() to 0xFFC7D2E8.toInt()
        }
        return TextView(activity).apply {
            text = status.uppercase()
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(fg)
            background = rounded(bg, dp(999), Color.TRANSPARENT, 0)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(10) }
        }
    }

    private fun chip(text: String, highlight: Boolean = false): View =
        TextView(activity).apply {
            this.text = text
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(if (highlight) 0xFFFED7AA.toInt() else 0xFFB6C2DC.toInt())
            background = rounded(
                if (highlight) accentA(0x22) else 0x12FFFFFF.toInt(),
                dp(9),
                if (highlight) accentA(0x44) else 0x1FFFFFFF.toInt(),
                dp(1),
            )
            setPadding(dp(9), dp(4), dp(9), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) }
        }

    private fun actionButton(
        text: String,
        bg: Int,
        fg: Int,
        onClick: () -> Unit,
    ): Button = Button(activity).apply {
        this.text = text
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        setTextColor(fg)
        background = rounded(bg, dp(12), 0x22FFFFFF.toInt(), dp(1))
        setPadding(dp(8), dp(9), dp(8), dp(9))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { onClick() }
    }

    // ── Actions ────────────────────────────────────────────────────────────

    fun doAction(service: String, action: String) {
        Thread {
            try {
                val conn = URL("$DASH_URL/api").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 3000
                conn.readTimeout = 8000
                conn.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject().put("action", action).put("service", service).toString()
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val ok = code in 200..399
                conn.disconnect()
                activity.runOnUiThread {
                    val label = services[service]?.label ?: service
                    if (ok) {
                        Toast.makeText(
                            activity,
                            when (action) {
                                "start" -> "▶ $label started"
                                "stop" -> "■ $label stopped"
                                "restart" -> "↻ $label restarted"
                                else -> "$label: ok"
                            },
                            Toast.LENGTH_SHORT,
                        ).show()
                        refreshNow()
                    } else {
                        Toast.makeText(activity, "$label: action failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Dashboard unreachable: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    fun onTerminalUpdateFinished(service: String) {
        Toast.makeText(activity, "✅ ${services[service]?.label ?: service} updated — restarting…", Toast.LENGTH_LONG).show()
        doAction(service, "restart")
        refreshNow()
    }

    private fun showQuickCommands(service: String, svc: Service) {
        val items = QUICK_CMDS[service] ?: emptyList()
        if (items.isEmpty()) {
            Toast.makeText(activity, "No quick commands for $service", Toast.LENGTH_SHORT).show()
            return
        }
        val names = items.map { it.first }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("⌘ Quick — ${svc.label}")
            .setItems(names) { _, which ->
                val cmd = items[which].second
                activity.openTerminalForService(service, MainActivity.NATIVE_QUICK, cmd)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSkillsDialog() {
        val packs = serverManager.listSkillPacks()
        if (packs.isEmpty()) {
            Toast.makeText(activity, "No skill packs bundled", Toast.LENGTH_SHORT).show()
            return
        }
        val installed = serverManager.installedSkills()
        val labels = packs.map { p ->
            val state = if (p.id in installed) "✓ installed" else "— not installed"
            "${p.name}  ($state)\n${p.description}"
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("🧩 Skill packs (installed to ~/workspace/skills)")
            .setItems(labels) { _, which ->
                val pack = packs[which]
                val on = pack.id in installed
                val next = if (on) "Remove" else "Install"
                AlertDialog.Builder(activity)
                    .setTitle("${pack.name}")
                    .setMessage("${pack.description}\n\nGo ahead and $next this pack?")
                    .setPositiveButton(next) { _, _ ->
                        val ok = if (on) serverManager.removeSkill(pack) else serverManager.installSkill(pack)
                        Toast.makeText(
                            activity,
                            if (ok) "${next}d ${pack.name}" else "${next} failed",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .show()
    }

    private fun testProvidersDialog() {
        val msg = AlertDialog.Builder(activity)
            .setTitle("⚡ Testing providers…")
            .setMessage("")
            .setNegativeButton("Close", null)
            .create()
        msg.show()
        Thread {
            val lines = mutableListOf<String>()
            try {
                val conn = URL("$DASH_URL/api").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 20000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use {
                    it.write(JSONObject().put("action", "providers-test").toString().toByteArray())
                }
                val r = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                if (r.optBoolean("ok")) {
                    val arr = r.optJSONArray("providers")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val p = arr.optJSONObject(i)
                            val ok = p?.optBoolean("ok") == true
                            lines += buildString {
                                append(if (ok) "● " else "○ ")
                                append(p?.optString("label", "?") ?: "?")
                                append("  ")
                                append(
                                    if (ok) {
                                        "${p?.optInt("ms", 0)}ms · ${p?.optInt("models", 0)} models"
                                    } else {
                                        p?.optString("error", "no key") ?: "no key"
                                    },
                                )
                            }
                        }
                    }
                } else {
                    lines += "Error: ${r.optString("error", "unknown")}"
                }
            } catch (e: Exception) {
                lines += "Dashboard unreachable: ${e.message}"
            }
            activity.runOnUiThread {
                msg.setMessage(lines.joinToString("\n").ifEmpty { "No providers configured" })
                msg.setTitle("⚡ Provider health")
            }
        }.start()
    }

    private fun confirmResetAll() {
        AlertDialog.Builder(activity)
            .setTitle("Reset all data?")
            .setMessage("Debug build only. Stops every server and deletes the Linux prefix, workspace and state. The next launch is a fresh first boot.")
            .setPositiveButton("Wipe") { _, _ ->
                activity.resetAllData {
                    activity.runOnUiThread {
                        Toast.makeText(activity, "All data wiped — reopen to reinstall", Toast.LENGTH_LONG).show()
                        activity.finishAffinity()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun rounded(bg: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(bg)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
}
