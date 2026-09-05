package com.codex.mobile

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

/**
 * Native on-device terminal backed by the real Termux terminal emulator
 * (com.termux:terminal-view + terminal-emulator). Supports multiple tabs —
 * one real PTY session per service — all inside the shared workspace.
 */
class NativeTerminal(
    private val activity: MainActivity,
    private val serverManager: CodexServerManager,
) {
    private val sessions = LinkedHashMap<String, TerminalSession>()
    private val updateModes = HashMap<String, Boolean>()
    private val cwds = HashMap<String, String>()
    private var activeService: String? = null

    private val terminalViewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = scale
        override fun onSingleTapUp(e: MotionEvent) {}
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = activeService != null
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession?): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(e: MotionEvent): Boolean = false
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(cp: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
        override fun onEmulatorSet() {}
        override fun logError(tag: String, msg: String) {}
        override fun logWarn(tag: String, msg: String) {}
        override fun logInfo(tag: String, msg: String) {}
        override fun logDebug(tag: String, msg: String) {}
        override fun logVerbose(tag: String, msg: String) {}
        override fun logStackTraceWithMessage(tag: String, msg: String, e: Exception) {}
        override fun logStackTrace(tag: String, e: Exception) {}
    }

    val overlay: FrameLayout = FrameLayout(activity).apply {
        setBackgroundColor(0xFF05070D.toInt())
        setVisibility(View.GONE)
    }

    private val titleView: TextView
    private val subtitleView: TextView
    private val tabStrip: LinearLayout

    val terminalView: TerminalView = TerminalView(activity, null).apply {
        setTextSize(18)
        setTypeface(Typeface.MONOSPACE)
        setTerminalViewClient(terminalViewClient)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    init {
        val root = overlay
        root.addView(
            terminalView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // Top bar: title + tab strip + actions
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6121A2B.toInt())
            setPadding(dp(16), dp(10), dp(12), dp(8))
        }
        val topRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleView = TextView(activity).apply {
            text = "Terminal"
            setTextColor(0xFFEAF0FF.toInt())
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }
        subtitleView = TextView(activity).apply {
            text = "workspace"
            setTextColor(0xFF8FA0BF.toInt())
            textSize = 11.5f
        }
        titleCol.addView(titleView)
        titleCol.addView(subtitleView)

        val sendExitBtn = Button(activity).apply {
            text = "EXIT"
            textSize = 12f
            isAllCaps = false
            setTextColor(0xFFFECACA.toInt())
            setBackgroundColor(0x26F87171.toInt())
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { sendText("exit\n") }
        }
        val closeBtn = Button(activity).apply {
            text = "CLOSE"
            textSize = 12f
            isAllCaps = false
            setTextColor(0xFFFFD9C7.toInt())
            setBackgroundColor(0x33FF7849.toInt())
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { hide() }
        }
        topRow.addView(titleCol)
        topRow.addView(sendExitBtn)
        topRow.addView(closeBtn)
        bar.addView(topRow)

        tabStrip = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(tabStrip)

        val barParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        )
        root.addView(bar, barParams)
    }

    fun isShowing(): Boolean = overlay.visibility == View.VISIBLE

    /**
     * Open (or focus) a terminal tab for [service]. Pass [updateCommand] to
     * run an update and restart the service on exit, [runCommand] to execute
     * a quick command, or [cwd] to start the shell in another directory.
     */
    fun show(
        service: String,
        updateCommand: String? = null,
        runCommand: String? = null,
        cwd: String? = null,
    ) {
        val paths = BootstrapInstaller.getPaths(activity)
        val workspace = File(paths.homeDir, CodexServerManager.WORKSPACE_DIR).absolutePath
        val targetCwd = cwd ?: workspace

        val existing = sessions[service]
        if (existing == null) {
            val prefix = paths.prefixDir
            val shell = "$prefix/bin/bash"
            val env = serverManager.buildEnvironment(paths)
                .map { (k, v) -> "$k=$v" }
                .toTypedArray()
            val s = TerminalSession(shell, targetCwd, arrayOf("bash"), env, null, sessionClientFor(service))
            sessions[service] = s
            cwds[service] = targetCwd
            updateModes[service] = updateCommand != null
        } else {
            updateModes[service] = updateCommand != null
        }

        overlay.visibility = View.VISIBLE
        activateTab(service)

        if (updateCommand != null) {
            terminalView.postDelayed({
                sendText("cd \"$targetCwd\"\n$updateCommand\necho \"--- update finished ---\"\nexit\n")
            }, 350)
        } else if (runCommand != null) {
            terminalView.postDelayed({
                sendText("cd \"$targetCwd\"\n$runCommand\n")
            }, 350)
        }
    }

    /** Open a shell tab rooted at [cwd] (used by the workspace file browser). */
    fun showShell(cwd: String) {
        show("shell", cwd = cwd)
    }

    private fun activateTab(service: String) {
        val s = sessions[service] ?: return
        activeService = service
        val cwd = cwds[service] ?: ""
        titleView.text = "$service — Terminal"
        subtitleView.text = "real Termux PTY · $cwd"
        terminalView.attachSession(s)
        terminalView.requestFocus()
        renderTabs()
    }

    private fun renderTabs() {
        tabStrip.removeAllViews()
        for ((key, _) in sessions) {
            val active = key == activeService
            val tab = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(6), dp(4))
                setBackgroundColor(if (active) 0x33FF7849.toInt() else 0x0FFFFFFF.toInt())
                background = if (active) {
                    roundedDrawable(0xFF2A1A0E.toInt(), dp(8), 0x55FF7849.toInt(), dp(1))
                } else {
                    roundedDrawable(0x33FFFFFF.toInt(), dp(8), 0x1FFFFFFF.toInt(), dp(1))
                }
            }
            val label = TextView(activity).apply {
                text = key
                textSize = 11f
                typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (active) 0xFFFFD9C7.toInt() else 0xFFB6C2DC.toInt())
                setPadding(dp(6), 0, dp(2), 0)
                setOnClickListener { activateTab(key) }
            }
            val close = TextView(activity).apply {
                text = " ×"
                textSize = 13f
                setTextColor(0xFF8FA0BF.toInt())
                setPadding(0, 0, dp(6), 0)
                setOnClickListener { closeTab(key) }
            }
            tab.addView(label)
            tab.addView(close)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) }
            tabStrip.addView(tab, lp)
        }
    }

    private fun closeTab(service: String) {
        sessions.remove(service)?.let { s ->
            try {
                if (s.isRunning) s.finishIfRunning()
            } catch (_: Exception) {
            }
        }
        updateModes.remove(service)
        cwds.remove(service)
        if (activeService == service) {
            activeService = sessions.keys.firstOrNull()
            if (activeService == null) {
                hide()
                return
            }
            activateTab(activeService!!)
        } else {
            renderTabs()
        }
    }

    fun hide() {
        for ((key, s) in sessions) {
            try {
                if (s.isRunning) s.finishIfRunning()
            } catch (_: Exception) {
            }
            sessions.remove(key)
        }
        updateModes.clear()
        cwds.clear()
        activeService = null
        overlay.visibility = View.GONE
    }

    fun sendText(text: String) {
        val key = activeService ?: return
        val s = sessions[key] ?: return
        try {
            val data = text.toByteArray(Charsets.UTF_8)
            s.write(data, 0, data.size)
        } catch (_: Exception) {
        }
    }

    private fun sessionClientFor(service: String): TerminalSessionClient =
        object : TerminalSessionClient {
            override fun onTextChanged(session: TerminalSession) {}
            override fun onTitleChanged(session: TerminalSession) {}
            override fun onSessionFinished(session: TerminalSession) {
                activity.runOnUiThread {
                    // Only react to the session that is still registered.
                    val svc = keyFor(session) ?: return@runOnUiThread
                    if (updateModes[svc] == true) {
                        updateModes.remove(svc)
                        sessions.remove(svc)
                        cwds.remove(svc)
                        if (activeService == svc) activeService = sessions.keys.firstOrNull()
                        activity.onTerminalUpdateFinished(svc)
                    } else {
                        if (sessions.containsKey(svc)) closeTab(svc)
                    }
                }
            }
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
            override fun onPasteTextFromClipboard(session: TerminalSession) {}
            override fun onBell(session: TerminalSession) {}
            override fun onColorsChanged(session: TerminalSession) {}
            override fun onTerminalCursorStateChange(state: Boolean) {}
            override fun getTerminalCursorStyle(): Int? = null
            override fun logError(tag: String, msg: String) {}
            override fun logWarn(tag: String, msg: String) {}
            override fun logInfo(tag: String, msg: String) {}
            override fun logDebug(tag: String, msg: String) {}
            override fun logVerbose(tag: String, msg: String) {}
            override fun logStackTraceWithMessage(tag: String, msg: String, e: Exception) {}
            override fun logStackTrace(tag: String, e: Exception) {}
        }

    private fun keyFor(session: TerminalSession): String? =
        sessions.entries.firstOrNull { it.value === session }?.key

    private fun roundedDrawable(bg: Int, radius: Int, strokeColor: Int, strokeWidth: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(bg)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

    private fun dp(v: Int): Int =
        (v * activity.resources.displayMetrics.density).toInt()
}
