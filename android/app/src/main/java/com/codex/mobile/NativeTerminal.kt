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
 * (com.termux:terminal-view + terminal-emulator). The shell is spawned on a
 * real POSIX PTY by Termux's own native JNI code inside the app's Termux
 * prefix, so it behaves exactly like Termux — real bash, real signals,
 * resizing, soft-keyboard input.
 */
class NativeTerminal(
    private val activity: MainActivity,
    private val serverManager: CodexServerManager,
) {
    private var session: TerminalSession? = null
    private var currentService: String? = null
    private var updateMode: Boolean = false

    private val terminalViewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = scale
        override fun onSingleTapUp(e: MotionEvent) {}
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
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

        // Top bar: service title, workspace, close button
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xE6121A2B.toInt())
            setPadding(dp(16), dp(10), dp(12), dp(10))
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

        val closeBtn = Button(activity).apply {
            text = "CLOSE"
            textSize = 12f
            isAllCaps = false
            setTextColor(0xFFFFD9C7.toInt())
            setBackgroundColor(0x33FF7849.toInt())
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { hide() }
        }
        val sendExitBtn = Button(activity).apply {
            text = "EXIT"
            textSize = 12f
            isAllCaps = false
            setTextColor(0xFFFECACA.toInt())
            setBackgroundColor(0x26F87171.toInt())
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { sendText("exit\n") }
        }
        bar.addView(titleCol)
        bar.addView(sendExitBtn)
        bar.addView(closeBtn)

        val barParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        )
        root.addView(bar, barParams)
    }

    fun isShowing(): Boolean = overlay.visibility == View.VISIBLE

    /**
     * Open a real Termux terminal for [service] running bash inside the
     * shared workspace. When [updateCommand] is given the command is executed
     * in the terminal and, once the shell exits, the service is restarted via
     * [MainActivity.onTerminalUpdateFinished].
     */
    fun show(service: String, updateCommand: String? = null) {
        hideSafely()
        currentService = service
        updateMode = updateCommand != null

        val paths = BootstrapInstaller.getPaths(activity)
        val prefix = paths.prefixDir
        val shell = "$prefix/bin/bash"
        val workspace = File(paths.homeDir, CodexServerManager.WORKSPACE_DIR).absolutePath
        val env = serverManager.buildEnvironment(paths)
            .map { (k, v) -> "$k=$v" }
            .toTypedArray()

        titleView.text = "$service — Terminal"
        subtitleView.text = "real Termux PTY · $workspace"

        val s = TerminalSession(shell, workspace, arrayOf("bash"), env, null, sessionClient)
        session = s

        // The overlay is attached to the activity root once in MainActivity.
        overlay.visibility = View.VISIBLE

        // attachSession() spawns the shell on a PTY and initializes the emulator
        terminalView.attachSession(s)
        terminalView.requestFocus()

        if (updateCommand != null) {
            terminalView.postDelayed({
                sendText("cd \"$workspace\"\n$updateCommand\necho \"--- update finished ---\"\nexit\n")
            }, 350)
        }
    }

    fun hide() {
        hideSafely()
        overlay.visibility = View.GONE
    }

    private fun hideSafely() {
        updateMode = false
        currentService = null
        session?.let { s ->
            try {
                if (s.isRunning) s.finishIfRunning()
            } catch (_: Exception) {
            }
        }
        session = null
    }

    fun sendText(text: String) {
        val s = session ?: return
        try {
            val data = text.toByteArray(Charsets.UTF_8)
            s.write(data, 0, data.size)
        } catch (_: Exception) {
        }
    }

    private fun dp(v: Int): Int =
        (v * activity.resources.displayMetrics.density).toInt()

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(session: TerminalSession) {}
        override fun onTitleChanged(session: TerminalSession) {}
        override fun onSessionFinished(session: TerminalSession) {
            activity.runOnUiThread {
                // Only react to the session that is currently attached; a
                // previously closed session can finish late (SIGKILL from
                // hideSafely) and must not restart a newer update.
                if (session !== this@NativeTerminal.session) return@runOnUiThread
                val svc = currentService
                if (svc != null && updateMode) {
                    hideSafely()
                    activity.onTerminalUpdateFinished(svc)
                } else {
                    hide()
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
}
