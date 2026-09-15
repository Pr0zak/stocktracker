package com.stocktracker.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.background
import com.stocktracker.app.R
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.flow.first
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The card a home-screen widget is drawn on: its colour and how much of the wallpaper shows through.
 *
 * Both are one app-wide setting rather than per-widget state, because two of the three widget types
 * (watchlist and portfolio) have no configuration screen of their own and would otherwise have no
 * way to be customised at all.
 *
 * Every choice here is dark on purpose. The widgets' foreground inks — the light body text and the
 * pastel gain/loss greens and reds — are the dark theme's, and this app has already measured what
 * they do on a light surface: 1.66:1 and 2.63:1, which is why the light theme was removed rather
 * than finished. Offering a white or pale background would reintroduce exactly that defect on the
 * one surface the user cannot adjust the text colour of. Transparency is the knob for "lighter".
 */
object WidgetBackground {

    /** The original fixed card colour, and still the default. Matches `res/drawable/widget_background.xml`. */
    const val DEFAULT_ARGB = 0xFF1C1B21L

    /** Fully opaque. */
    const val DEFAULT_TRANSPARENCY = 0

    /** Corner radius of the widget card, in dp. Matches the drawable this replaces. */
    const val CORNER_RADIUS_DP = 20f

    /**
     * Upper bound on the generated bitmap, in pixels of area.
     *
     * The card is a flat fill, so it upscales without visible loss everywhere except the corner
     * arc, and a widget-sized ARGB_8888 bitmap on a 3x display would be several megabytes crossing
     * a Binder transaction on every update. Capping by AREA rather than by long edge bounds the
     * memory for a tall or square widget too, which a long-edge cap does not.
     */
    private const val MAX_BITMAP_PIXELS = 90_000

    /** Fallback render size when the host reports no usable widget size. */
    const val FALLBACK_W_PX = 300
    const val FALLBACK_H_PX = 150

    /** Selectable card colours, darkest first. Labels are shown in Settings. */
    val COLOR_CHOICES: List<Pair<String, Long>> = listOf(
        "Charcoal" to DEFAULT_ARGB,
        "Black" to 0xFF000000L,
        "Graphite" to 0xFF26262BL,
        "Midnight" to 0xFF141A26L,
        "Espresso" to 0xFF211A17L,
    )

    /**
     * Alpha channel (0-255) for a transparency percentage, where 0% is solid and 100% is invisible.
     *
     * The stored value is the percentage the user sees, not its complement, so that the number in
     * the setting and the number on the slider can never disagree. This is the single place the two
     * senses are converted between.
     */
    fun alphaFor(transparencyPct: Int): Int =
        (100 - transparencyPct.coerceIn(0, 100)) * 255 / 100

    /** [colorArgb] with its alpha replaced by [transparencyPct]. */
    fun argbWith(colorArgb: Long, transparencyPct: Int): Int =
        (colorArgb.toInt() and 0x00FFFFFF) or (alphaFor(transparencyPct) shl 24)

    /**
     * Scale factor applied to a widget's pixel size before rendering, so the bitmap stays under
     * [MAX_BITMAP_PIXELS]. Uniform in both axes: the aspect ratio has to survive, because the
     * bitmap is stretched back to the full widget with FillBounds and any aspect error there shows
     * up as an oval corner.
     */
    fun scaleFor(widthPx: Int, heightPx: Int): Float {
        val area = widthPx.toLong() * heightPx.toLong()
        if (area <= MAX_BITMAP_PIXELS || area <= 0L) return 1f
        return sqrt(MAX_BITMAP_PIXELS.toDouble() / area.toDouble()).toFloat()
    }

    /**
     * The stored card settings, or the defaults if the preference store cannot be read.
     *
     * Read straight from the settings store inside `provideGlance` rather than pushed into Glance
     * state by the refresh worker, which is how `hideZeroCents` reaches the widgets. Appearance has
     * to change the moment the slider moves; routing it through a refresh would leave the old
     * colour on the home screen until the next fetch was due — up to two hours on the slowest
     * refresh setting.
     */
    suspend fun current(): Pair<Long, Int> = runCatching {
        ServiceLocator.settingsStore.widgetBackgroundArgb.first() to
            ServiceLocator.settingsStore.widgetBackgroundTransparency.first()
    }.getOrDefault(DEFAULT_ARGB to DEFAULT_TRANSPARENCY)

    /** Draws the rounded card. Corners are left transparent so the launcher shows through them. */
    fun render(widthPx: Int, heightPx: Int, argb: Int, radiusPx: Float): Bitmap {
        val w = max(1, widthPx)
        val h = max(1, heightPx)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb }
        Canvas(bitmap).drawRoundRect(
            RectF(0f, 0f, w.toFloat(), h.toFloat()),
            radiusPx,
            radiusPx,
            paint,
        )
        return bitmap
    }
}

/**
 * Applies the user's chosen widget card as this element's background.
 *
 * A widget still on the defaults keeps the static XML drawable: no bitmap is allocated and the
 * render path is unchanged from before this setting existed, so the common case cannot regress.
 */
@Composable
fun GlanceModifier.widgetBackground(colorArgb: Long, transparencyPct: Int): GlanceModifier {
    if (colorArgb == WidgetBackground.DEFAULT_ARGB &&
        transparencyPct.coerceIn(0, 100) == WidgetBackground.DEFAULT_TRANSPARENCY
    ) {
        return this.background(ImageProvider(R.drawable.widget_background))
    }

    val density = LocalContext.current.resources.displayMetrics.density
    val size = LocalSize.current
    val argb = WidgetBackground.argbWith(colorArgb, transparencyPct)

    val bitmap = remember(size, colorArgb, transparencyPct, density) {
        val wPx = (size.width.value * density).roundToInt()
        val hPx = (size.height.value * density).roundToInt()
        val usable = wPx > 0 && hPx > 0
        val baseW = if (usable) wPx else WidgetBackground.FALLBACK_W_PX
        val baseH = if (usable) hPx else WidgetBackground.FALLBACK_H_PX
        val k = WidgetBackground.scaleFor(baseW, baseH)
        WidgetBackground.render(
            widthPx = (baseW * k).roundToInt(),
            heightPx = (baseH * k).roundToInt(),
            argb = argb,
            // The radius is scaled by the same factor as the canvas, so stretching the bitmap back
            // to the widget's real size reproduces exactly a 20dp corner.
            radiusPx = WidgetBackground.CORNER_RADIUS_DP * density * k,
        )
    }
    return this.background(ImageProvider(bitmap))
}
