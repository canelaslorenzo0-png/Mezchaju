package com.codex.mobile

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.File

/**
 * Native file browser for the shared ~/workspace: navigate folders, open a
 * terminal there, share or delete files (SAF-backed sharing via FileProvider).
 */
class NativeFiles(private val activity: MainActivity) {

    var overlay: FrameLayout = FrameLayout(activity).apply {
        setBackgroundColor(0xF005070D.toInt())
        setVisibility(View.GONE)
    }

    private var currentDir: File? = null
    private var selected: File? = null

    private val pathText: TextView = TextView(activity).apply {
        setTextColor(0xFFEAF0FF.toInt())
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val listBox: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
    }

    private val scroll: ScrollView = ScrollView(activity).apply {
        isVerticalScrollBarEnabled = true
        addView(listBox)
    }

    init {
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xE6121A2B.toInt())
            setPadding(dp(14), dp(10), dp(12), dp(10))
        }
        header.addView(pathText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            Button(activity).apply {
                text = "⬆ UP"
                textSize = 12f
                isAllCaps = false
                setTextColor(0xFFA5F3FC.toInt())
                setBackgroundColor(0x1F22D3EE.toInt())
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener { goUp() }
            },
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

        val actionBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xF00A0F1A.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        actionBar.addView(actionButton("▶ TERMINAL HERE") {
            val dir = currentDir ?: return@actionButton
            activity.openTerminalAt(dir.absolutePath)
        })
        actionBar.addView(actionButton("↗ SHARE") {
            val f = selected ?: return@actionButton
            activity.shareFile(f)
        })
        actionBar.addView(actionButton("🗑 DELETE") {
            val f = selected ?: return@actionButton
            confirmDelete(f)
        })

        overlay.addView(
            header,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )
        overlay.addView(scroll)
        overlay.addView(
            actionBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
    }

    fun show() {
        val ws = File(activity.workspacePath())
        currentDir = ws
        selected = null
        overlay.visibility = View.VISIBLE
        refresh()
    }

    fun hide() {
        overlay.visibility = View.GONE
    }

    fun isShowing(): Boolean = overlay.visibility == View.VISIBLE

    private fun goUp() {
        val dir = currentDir ?: return
        val parent = dir.parentFile ?: return
        val ws = File(activity.workspacePath())
        if (dir == ws || parent == null) return
        currentDir = parent
        selected = null
        refresh()
    }

    private fun refresh() {
        val dir = currentDir ?: return
        pathText.text = dir.absolutePath
        listBox.removeAllViews()
        val children = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
        if (children.isEmpty()) {
            listBox.addView(
                TextView(activity).apply {
                    text = "empty folder"
                    setTextColor(0xFF8FA0BF.toInt())
                    textSize = 12f
                    setPadding(0, dp(24), 0, 0)
                },
            )
            return
        }
        for (f in children) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundColor(if (selected == f) 0x22FF7849.toInt() else 0x00FFFFFF)
            }
            row.addView(
                TextView(activity).apply {
                    text = if (f.isDirectory) "📁 " else "📄 "
                    textSize = 16f
                },
            )
            row.addView(
                TextView(activity).apply {
                    text = f.name + if (f.isDirectory) "/" else ""
                    textSize = 13.5f
                    setTextColor(if (f.isDirectory) 0xFFDCE6FF.toInt() else 0xFFB6C2DC.toInt())
                    typeface = if (f.isDirectory) Typeface.DEFAULT_BOLD else Typeface.MONOSPACE
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            row.setOnClickListener {
                selected = f
                if (f.isDirectory) {
                    currentDir = f
                    selected = null
                    refresh()
                } else {
                    refresh()
                }
            }
            listBox.addView(row)
        }
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(activity)
            .setTitle("Delete ${f.name}?")
            .setMessage("This permanently removes the file${if (f.isDirectory) "/folder and all contents" else ""}.")
            .setPositiveButton("Delete") { _, _ ->
                val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
                Toast.makeText(activity, if (ok) "Deleted" else "Delete failed", Toast.LENGTH_SHORT).show()
                selected = null
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun actionButton(text: String, onClick: () -> Unit): Button =
        Button(activity).apply {
            this.text = text
            textSize = 12f
            isAllCaps = false
            setTextColor(0xFFEAF0FF.toInt())
            setBackgroundColor(0x22FFFFFF.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
}
