package com.gl1ch5.stabcam.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.gl1ch5.stabcam.R
import com.gl1ch5.stabcam.util.Logger

/** Live log viewer: follows new lines as they arrive. */
class LogActivity : Activity() {
    private lateinit var text: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            setPadding(dp(12), dp(40), dp(12), dp(12))
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            this.text = t; isAllCaps = false; setOnClickListener { f() }
        }.also { bar.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        btn("Копировать") {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("log", Logger.snapshot()))
            Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
        }
        btn("Поделиться") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Logger.snapshot()), "Лог"))
        }
        btn("Очистить") { Logger.clear(); text.text = "" }
        root.addView(bar)
        text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(getColor(R.color.text))
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(text) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        text.text = Logger.snapshot()
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        Logger.listener = { line ->
            runOnUiThread {
                text.append("\n$line")
                scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Logger.listener = null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
