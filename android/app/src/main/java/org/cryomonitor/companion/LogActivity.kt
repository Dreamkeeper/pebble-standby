package org.cryomonitor.companion

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** In-app log viewer with share/clear (no file manager needed). */
class LogActivity : AppCompatActivity() {

    private lateinit var text: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Refresh"; setOnClickListener { refresh() }
        })
        row.addView(Button(this).apply {
            text = "Share"
            setOnClickListener {
                val f = CmLog.exportForShare(this@LogActivity)
                if (f == null) {
                    android.widget.Toast.makeText(this@LogActivity,
                        "Could not write log file", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    Ui.shareFile(this@LogActivity, f, "Standby logs", "Share logs")
                }
            }
        })
        row.addView(Button(this).apply {
            text = "Clear"
            setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this@LogActivity)
                    .setTitle("Clear all logs?")
                    .setMessage("Deletes the in-app log and the on-disk daily files. This cannot be undone.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear") { _, _ -> CmLog.clear(); refresh() }
                    .show()
            }
        })
        col.addView(row)

        text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(Ui.dp(context, 12), Ui.dp(context, 12),
                       Ui.dp(context, 12), Ui.dp(context, 12))
        }
        scroll = ScrollView(this).apply { addView(text) }
        col.addView(scroll)
        Ui.applySystemInsets(col)
        setContentView(col)
        refresh()
    }

    private fun refresh() {
        text.text = CmLog.dump().ifEmpty { "(no log lines yet)" }
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
