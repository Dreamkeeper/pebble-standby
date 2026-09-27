package org.cryomonitor.companion

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.MaterialColors

/**
 * Material 3 helpers for the programmatic-views approach (design D1-D3
 * of platform-ui-guidelines): dp grid, type scale, theme color roles.
 */
object Ui {
    /** M3 4dp grid: all spacing goes through here — never raw pixels. */
    fun dp(c: Context, units: Int): Int =
        (units * c.resources.displayMetrics.density).toInt()

    // Type scale (Material 3 text appearances on plain TextViews)
    fun TextView.headline() = setTextAppearance(
        com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
    fun TextView.title() = setTextAppearance(
        com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
    fun TextView.body() = setTextAppearance(
        com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
    fun TextView.caption() = setTextAppearance(
        com.google.android.material.R.style.TextAppearance_Material3_BodySmall)

    // Theme color roles (resolved against the view's themed context, so
    // dynamic color and dark mode both apply automatically)
    fun errorContainer(v: View): Int = MaterialColors.getColor(
        v, com.google.android.material.R.attr.colorErrorContainer)
    fun onErrorContainer(v: View): Int = MaterialColors.getColor(
        v, com.google.android.material.R.attr.colorOnErrorContainer)
    fun error(v: View): Int = MaterialColors.getColor(
        v, com.google.android.material.R.attr.colorError)
    fun onError(v: View): Int = MaterialColors.getColor(
        v, com.google.android.material.R.attr.colorOnError)
    fun primaryContainer(v: View): Int = MaterialColors.getColor(
        v, com.google.android.material.R.attr.colorPrimaryContainer)
    fun onPrimaryContainer(v: View): Int = MaterialColors.getColor(
        v, com.google.android.material.R.attr.colorOnPrimaryContainer)
    fun surfaceVariant(v: View): Int = MaterialColors.getColor(
        v, com.google.android.material.R.attr.colorSurfaceVariant)
    fun onSurfaceVariant(v: View): Int = MaterialColors.getColor(
        v, com.google.android.material.R.attr.colorOnSurfaceVariant)

    /**
     * M3 role separation for live DATA vs. instruction PROSE: measurements
     * render as monospace tabular figures on a container surface, so a
     * changing number can never be mistaken for guidance text.
     */
    fun TextView.dataCard() {
        typeface = android.graphics.Typeface.MONOSPACE
        textSize = 16f
        setBackgroundColor(primaryContainer(this))
        setTextColor(onPrimaryContainer(this))
        setPadding(dp(context, 12), dp(context, 10),
                   dp(context, 12), dp(context, 10))
    }

    /**
     * Android 15+ forces edge-to-edge: without this, content draws under
     * the status bar / camera cutout / gesture bar. Pads the root view by
     * the system-bar + cutout insets; the root's background still paints
     * the full window, so alarm screens stay full-bleed.
     */
    fun applySystemInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    /** ACTION_SEND a file through the FileProvider (never a text extra —
     *  large extras kill the process: Binder limit, field 2026-09-07). */
    fun shareFile(activity: android.app.Activity, file: java.io.File, subject: String,
                  title: String, mime: String = "text/plain") {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            activity, activity.packageName + ".fileprovider", file)
        activity.startActivity(android.content.Intent.createChooser(
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mime
                putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, title))
    }
}
