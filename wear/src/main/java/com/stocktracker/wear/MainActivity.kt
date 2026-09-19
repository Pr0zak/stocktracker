package com.stocktracker.wear

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.stocktracker.shared.wearContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Not a configuration screen -- WGT-7 deliberately has no config UI on the watch, it always follows
 * whatever the phone's widgets are already set to show. Tapping the tile/complication lands here
 * mainly so there is somewhere to land, and so a watch that has never received a push says so in
 * plain language instead of showing a blank screen.
 *
 * Plain [android.widget] views rather than Wear Compose: this screen has no interaction and no
 * layout complexity, and skipping Compose keeps this module's dependency surface to exactly what
 * the tile/complication need (see wear/build.gradle.kts).
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var title: TextView
    private lateinit var headline: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        title = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE) }
        headline = TextView(this).apply { textSize = 22f; setTextColor(Color.WHITE) }
        status = TextView(this).apply { textSize = 12f; setTextColor(Color.LTGRAY) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(24, 24, 24, 24)
            addView(title)
            addView(headline)
            addView(status)
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        scope.launch {
            val snapshot = com.stocktracker.wear.data.WearRepository.snapshotOrHydrate(applicationContext)
            val content = wearContent(snapshot, nowMs = System.currentTimeMillis())
            title.text = WearText.title(content)
            headline.text = WearText.headline(content) ?: ""
            status.text = WearText.statusLine(content) ?: ""
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
