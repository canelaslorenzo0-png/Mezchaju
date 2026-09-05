package com.codex.mobile

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CodexMainActivity"

        const val NATIVE_TERMINAL = "terminal"
        const val NATIVE_LAUNCH = "launch"
        const val NATIVE_UPDATE = "update"
    }

    private lateinit var webView: WebView
    private lateinit var loadingOverlay: View
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var loadingRing: android.widget.ImageView
    private lateinit var agentCore: TextView
    private lateinit var serverManager: CodexServerManager
    private lateinit var nativeDashboard: NativeDashboard
    private lateinit var nativeTerminal: NativeTerminal
    private lateinit var webBackBar: View
    private var showingWeb = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serverManager = CodexServerManager(this)
        nativeDashboard = NativeDashboard(this, serverManager)
        nativeTerminal = NativeTerminal(this, serverManager)

        // Attach the native dashboard + terminal overlays above the WebView;
        // the WebView is only used for "Open UI" pages now.
        (findViewById<ViewGroup>(android.R.id.content)).apply {
            addView(
                nativeDashboard.view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                nativeTerminal.overlay,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            nativeDashboard.view.visibility = View.GONE
        }

        webView = findViewById(R.id.webView)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        statusText = findViewById(R.id.statusText)
        statusDetail = findViewById(R.id.statusDetail)
        loadingRing = findViewById(R.id.loadingRing)
        agentCore = findViewById(R.id.agentCore)
        webBackBar = findViewById(R.id.webBackBar)
        findViewById<View>(R.id.webBackBtn).setOnClickListener { showNativeHome() }

        loadingOverlay.startAnimation(AnimationUtils.loadAnimation(this, R.anim.overlay_fade))
        loadingRing.startAnimation(AnimationUtils.loadAnimation(this, R.anim.ring_spin))
        agentCore.startAnimation(AnimationUtils.loadAnimation(this, R.anim.core_pulse))
        for (id in arrayOf(R.id.dot1, R.id.dot2, R.id.dot3, R.id.dot4)) {
            findViewById<View>(id).startAnimation(AnimationUtils.loadAnimation(this, R.anim.dot_pulse))
        }

        requestBatteryOptimizationExemption()
        startForegroundService()
        setupWebView()
        startSetupFlow()
    }

    override fun onDestroy() {
        super.onDestroy()
        nativeDashboard.stopPolling()
        serverManager.stopServer()
        stopService(Intent(this, CodexForegroundService::class.java))
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        try {
            @Suppress("BatteryLife")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not request battery optimization exemption: ${e.message}")
        }
    }

    fun openWebUrl(url: String) {
        runOnUiThread {
            showingWeb = true
            webView.visibility = View.VISIBLE
            webView.startAnimation(AnimationUtils.loadAnimation(this, R.anim.view_fade_in))
            webView.bringToFront()
            webBackBar.visibility = View.VISIBLE
            webBackBar.bringToFront()
            webView.loadUrl(url)
        }
    }

    fun showNativeHome() {
        runOnUiThread {
            showingWeb = false
            webView.visibility = View.GONE
            webBackBar.visibility = View.GONE
            nativeDashboard.view.visibility = View.VISIBLE
            nativeDashboard.view.startAnimation(
                AnimationUtils.loadAnimation(this, R.anim.view_fade_in),
            )
        }
    }

    fun openTerminalForService(service: String, mode: String, command: String? = null) {
        runOnUiThread {
            showingWeb = false
            webView.visibility = View.GONE
            webBackBar.visibility = View.GONE
            nativeDashboard.view.visibility = View.GONE
            val cmd = when (mode) {
                NATIVE_LAUNCH -> if (command != null) "cd \"\$HOME/workspace\"\n" + command + "\n" else null
                NATIVE_UPDATE -> command
                else -> null
            }
            nativeTerminal.show(service, cmd)
            nativeTerminal.overlay.startAnimation(
                AnimationUtils.loadAnimation(this, R.anim.view_slide_up),
            )
        }
    }

    fun onTerminalUpdateFinished(service: String) {
        nativeDashboard.onTerminalUpdateFinished(service)
        showNativeHome()
    }

    /**
     * Debug-only wipe: stop every process and delete the prefix, workspace
     * and state so the next launch starts a clean first boot.
     */
    fun resetAllData(onDone: () -> Unit) {
        Thread {
            try {
                serverManager.stopServer()
                serverManager.resetAllData()
            } catch (e: Exception) {
                Log.e(TAG, "resetAllData failed: ${e.message}")
            }
            runOnUiThread { onDone() }
        }.start()
    }

    private fun startForegroundService() {
        val intent = Intent(this, CodexForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    @Deprecated("Use onBackPressedDispatcher")
    override fun onBackPressed() {
        if (nativeTerminal.isShowing()) {
            nativeTerminal.hide()
            showNativeHome()
        } else if (showingWeb) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                showNativeHome()
            }
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            setSupportZoom(false)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                url: String,
            ): Boolean = false
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.d(TAG, "[WebView] ${msg.sourceId()}:${msg.lineNumber()} ${msg.message()}")
                return true
            }
        }
    }

    private fun startSetupFlow() {
        showLoading(true)
        setStatus("Initializing…")

        Thread {
            try {
                runSetup()
            } catch (e: Exception) {
                Log.e(TAG, "Setup failed", e)
                runOnUiThread {
                    showError(e.message ?: "Unknown error")
                }
            }
        }.start()
    }

    private fun runSetup() {
        // Step 1: Extract bootstrap
        if (!BootstrapInstaller.isBootstrapInstalled(this)) {
            updateStatus("Extracting environment…")
            BootstrapInstaller.install(this) { msg -> updateStatus(msg) }
        }
        updateStatus("Environment ready")

        // Step 1b: Install proot (needed for dpkg/apt-get path remapping)
        if (!serverManager.isProotInstalled()) {
            updateStatus("Installing proot…", "Needed for package management")
            val prootOk = serverManager.installProot { msg -> updateDetail(msg) }
            if (!prootOk) {
                throw RuntimeException("Failed to install proot")
            }
        }
        updateStatus("proot ready")

        // Step 2: Install Node.js
        if (!serverManager.isNodeInstalled()) {
            updateStatus("Installing Node.js (first run)…", "This may take a few minutes")
            val nodeOk = serverManager.installNode { msg -> updateDetail(msg) }
            if (!nodeOk) {
                throw RuntimeException("Failed to install Node.js")
            }
        }
        updateStatus("Node.js ready")

        // Step 2b: Install Python
        if (!serverManager.isPythonInstalled()) {
            updateStatus("Installing Python…")
            val pyOk = serverManager.installPython { msg -> updateDetail(msg) }
            if (!pyOk) {
                Log.w(TAG, "Python install failed — continuing without it")
            }
        }

        // Step 2c: Install bionic-compat.js (Android platform shim for Node.js)
        serverManager.ensureBionicCompat()

        // Step 2d: Install OpenClaw
        if (!serverManager.isOpenClawInstalled()) {
            updateStatus("Installing build dependencies…")
            serverManager.installOpenClawDeps { msg -> updateDetail(msg) }

            updateStatus("Installing OpenClaw…", "This may take several minutes")
            val openclawOk = serverManager.installOpenClaw { msg -> updateDetail(msg) }
            if (!openclawOk) {
                Log.w(TAG, "OpenClaw install failed — continuing without it")
            } else {
                updateStatus("OpenClaw installed")
            }
        }

        // Step 3: Install agent harnesses (DeepSeek Harness + Claw Code)
        if (!serverManager.isHarnessInstalled()) {
            updateStatus("Installing agent harnesses…", "This may take a few minutes")
            val harnessOk = serverManager.installHarnesses { msg -> updateDetail(msg) }
            if (!harnessOk) {
                Log.w(TAG, "Harness install incomplete — continuing")
            }
        }
        updateStatus("Harnesses ready")

        // Step 3a: Extract web UI from APK assets (every launch).
        // Lite builds skip the bundled web UI entirely.
        if (!BuildConfig.LITE) {
            updateStatus("Updating web UI…")
            serverManager.installServerBundle { msg -> updateDetail(msg) }
        }

        updateStatus(if (BuildConfig.LITE) "Runtime ready (lite)" else "Runtime ready")

        // Step 3c: Write full-access config, provider config and default workspace
        serverManager.ensureFullAccessConfig()
        serverManager.ensureProvidersConfig()
        serverManager.ensureDefaultWorkspace()
        nativeDashboard.startPolling()

        // Step 4: Start CONNECT proxy (needed for native binary DNS/TLS)
        updateStatus("Starting network proxy…")
        if (!serverManager.startProxy()) {
            throw RuntimeException("Failed to start network proxy")
        }

        // Step 5: No forced login — providers are configured in the web UI
        updateStatus("Providers ready (OpenCodeZen, OpenRouter, Xkiro)")

        // Step 6: Configure and start OpenClaw
        if (serverManager.isOpenClawInstalled()) {
            updateStatus("Configuring OpenClaw…")
            serverManager.configureOpenClawAuth()

            updateStatus("Starting OpenClaw gateway…")
            serverManager.startOpenClawGateway()

            updateStatus("Starting OpenClaw Control UI…")
            serverManager.startOpenClawControlUiServer()
        }

        // Step 8: Start web server (skipped in lite — gateway + CLI only)
        if (BuildConfig.LITE) {
            updateStatus("Web UI skipped (lite build)")
        } else {
            updateStatus("Starting server…")
            val started = serverManager.startServer()
            if (!started) {
                throw RuntimeException("Failed to start server")
            }

            // Step 9: Wait for ready
            updateStatus("Waiting for server…")
            val ready = serverManager.waitForServer(timeoutMs = 90_000)
            if (!ready) {
                throw RuntimeException("Server did not start in time")
            }
        }

        // Step 10: Install + start the native dashboard (control panel with
        // per-service terminals, start/stop, shared workspace and updates)
        updateStatus("Installing dashboard…")
        serverManager.installDashboard { msg -> updateDetail(msg) }

        updateStatus("Starting dashboard…")
        if (!serverManager.startDashboard()) {
            Log.w(TAG, "Dashboard failed to start — falling back to web UI")
        }
        val dashReady = serverManager.waitForDashboard(timeoutMs = 20_000)
        if (!dashReady) {
            Log.w(TAG, "Dashboard not ready — falling back to web UI")
        }

        // Step 11: Show the native Kotlin dashboard. OpenClaw Control UI and
        // the web dashboard are one tap away from a service card and open
        // already authenticated (auth.mode none → no login prompt).
        runOnUiThread {
            showLoading(false)
            nativeDashboard.view.visibility = View.VISIBLE
            webView.visibility = View.GONE
            webBackBar.visibility = View.GONE
        }
    }

    // ── UI helpers ──────────────────────────────────────────────────────────

    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setPositiveButton(R.string.retry) { _, _ ->
                startSetupFlow()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showLoading(show: Boolean) {
        loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun setStatus(text: String, detail: String? = null) {
        statusText.text = text
        if (detail != null) {
            statusDetail.text = detail
            statusDetail.visibility = View.VISIBLE
        } else {
            statusDetail.visibility = View.GONE
        }
    }

    private fun updateStatus(text: String, detail: String? = null) {
        runOnUiThread {
            setStatus(text, detail)
            statusText.startAnimation(AnimationUtils.loadAnimation(this, R.anim.status_slide))
        }
    }

    private fun updateDetail(text: String) {
        runOnUiThread {
            statusDetail.text = text
            statusDetail.visibility = View.VISIBLE
        }
    }
}
