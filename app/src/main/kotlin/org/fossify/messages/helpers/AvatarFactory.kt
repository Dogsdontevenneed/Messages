package org.fossify.messages.helpers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache
import android.widget.ImageView

/**
 * Generated avatars for conversations and messages.
 *
 * Two things differ from the stock Fossify behaviour:
 *
 * **The label.** Fossify takes the first character of whatever string it is given, so every carrier
 * gateway (all of which start with "1069") rendered as the same "1". Here the label is resolved in
 * order of usefulness: contact name, then the 【brand】 signature at the start of the message (how
 * most Chinese services identify themselves), then the phone number.
 *
 * **The colour.** It used to be a hash of the name, which looked decorative but carried no meaning.
 * Colour now encodes state instead:
 *  - [Tone.ATTENTION] saturated orange -- unread, the only thing that should shout
 *  - [Tone.RECENT] pale apricot -- read, but active within the last day
 *  - [Tone.OUTGOING] green -- the last message was ours
 *  - [Tone.QUIET] pale blue -- everything else
 */
object AvatarFactory {

    enum class Tone { ATTENTION, RECENT, OUTGOING, QUIET }

    /** Shared with the unread badge so the list speaks one accent colour. */
    const val ACCENT_ORANGE = 0xFFEA580C.toInt()

    private class Style(val start: Int, val end: Int, val text: Int, val shadow: Boolean)

    private val STYLES = mapOf(
        // fully saturated, white glyph: this is the one that has to jump out of the list
        Tone.ATTENTION to Style(
            0xFFF97316.toInt(), 0xFFC2410C.toInt(), Color.WHITE, true
        ),
        // same hue as the unread orange (~24 degrees), just lighter -- an apricot tint drifted
        // past 30 degrees and read as yellow next to it
        Tone.RECENT to Style(
            0xFFFFE0C2.toInt(), 0xFFFBA35C.toInt(), 0xFF9A3412.toInt(), false
        ),
        // green rather than yellow: yellow sat too close to the orange pair and read as a third
        // orange, while green separates on hue alone and needs no brightness tuning
        Tone.OUTGOING to Style(
            0xFFBBF7D0.toInt(), 0xFF4ADE80.toInt(), 0xFF14532D.toInt(), false
        ),
        Tone.QUIET to Style(
            0xFFEFF6FF.toInt(), 0xFFBFDBFE.toInt(), 0xFF1E40AF.toInt(), false
        ),
    )

    // A message list only needs the avatars currently on screen, so the budget is capped in bytes
    // rather than entries. Capping by count let a 256px notification icon (262 KB on its own) sit
    // in the same cache as a 42px list avatar (~7 KB), which quietly cost megabytes.
    private const val CACHE_BYTES = 384 * 1024

    private val ORG_PREFIXES = arrayOf("中国", "中华", "全国", "国家")
    private val ORG_SUFFIXES = arrayOf(
        "股份有限公司", "有限责任公司", "有限公司", "集团", "客服", "通知", "服务", "中心", "公司"
    )

    private val SIGNATURE = Regex("[【\\[]([^】\\]]{2,16})[】\\]]")

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun into(imageView: ImageView, raw: String?, tone: Tone = Tone.QUIET) =
        into(imageView, raw, raw, null, raw, tone)

    /**
     * [seed] no longer affects the colour, it only keeps the cache key stable. The label is resolved
     * from the most recognisable of [displayName] / [snippet] signature / [number].
     */
    fun into(
        imageView: ImageView,
        seed: String?,
        displayName: String?,
        snippet: String?,
        number: String?,
        tone: Tone = Tone.QUIET,
    ) {
        val size = resolveSize(imageView)
        imageView.setImageBitmap(bitmap(imageView.context, seed, displayName, snippet, number, size, false, tone))
    }

    fun bitmap(context: android.content.Context, raw: String?, sizePx: Int): Bitmap =
        bitmap(context, raw, raw, null, raw, sizePx, false, Tone.QUIET)

    fun bitmap(
        context: android.content.Context,
        seed: String?,
        displayName: String?,
        snippet: String?,
        number: String?,
        sizePx: Int,
        circleSafe: Boolean = false,
        tone: Tone = Tone.QUIET,
    ): Bitmap {
        val source = seed?.trim().orEmpty()
        val label = labelOf(displayName, snippet, number)
        val key = "$source|$label|$sizePx|$circleSafe|$tone"
        // notification icons are built once and never reused, so they stay out of the cache
        if (!circleSafe) {
            cache.get(key)?.let { return it }
        }

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val style = STYLES[tone] ?: STYLES.getValue(Tone.QUIET)
        val bounds = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
        val radius = sizePx * 0.30f

        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, sizePx.toFloat(), sizePx.toFloat(),
                style.start, style.end, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(bounds, radius, radius, background)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.text
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = sizePx * 0.46f
            if (style.shadow) {
                setShadowLayer(sizePx * 0.06f, 0f, sizePx * 0.012f, 0x40000000)
            }
        }

        // Notification large icons get masked to a circle on most ROMs, so the glyph has to fit
        // inside the inscribed square (1/sqrt(2) of the full width) instead of the full canvas.
        val maxWidth = when {
            circleSafe -> sizePx * 0.62f
            label.length >= 5 -> sizePx * 0.92f
            else -> sizePx * 0.80f
        }
        val minSize = sizePx * 0.18f
        while (text.measureText(label) > maxWidth && text.textSize > minSize) {
            text.textSize -= sizePx * 0.02f
        }

        val baseline = sizePx / 2f - (text.descent() + text.ascent()) / 2f
        if (label.length == 2 && label[0].code >= 0x2E80) {
            // a hair of tracking, otherwise two CJK glyphs read as one cramped block
            val gap = text.textSize * 0.06f
            val first = text.measureText(label[0].toString())
            val second = text.measureText(label[1].toString())
            val start = sizePx / 2f - (first + gap + second) / 2f
            canvas.drawText(label[0].toString(), start + first / 2f, baseline, text)
            canvas.drawText(label[1].toString(), start + first + gap + second / 2f, baseline, text)
        } else {
            canvas.drawText(label, sizePx / 2f, baseline, text)
        }

        if (!circleSafe) {
            cache.put(key, bitmap)
        }
        return bitmap
    }

    fun labelOf(displayName: String?, snippet: String?, number: String?): String {
        val name = displayName?.trim().orEmpty()
        if (name.isNotEmpty() && !isNumeric(name)) {
            return labelOf(name)
        }

        val signature = snippet?.let { SIGNATURE.find(it)?.groupValues?.get(1)?.trim() }
        if (!signature.isNullOrEmpty()) {
            val fromSignature = labelOf(signature)
            if (fromSignature != "?") return fromSignature
        }

        return labelOf(number?.takeIf { it.isNotBlank() } ?: name)
    }

    fun labelOf(raw: String?): String {
        val source = raw?.trim().orEmpty()
        if (source.isEmpty()) return "?"

        if (isNumeric(source)) {
            val digits = source.filter { it.isDigit() }
            if (digits.isEmpty()) return "#"
            // five digit service numbers such as 10086 are worth showing in full, longer gateway
            // numbers are indistinguishable from each other past the last four digits
            return if (digits.length <= 5) digits else digits.takeLast(4)
        }

        var stripped = source
        ORG_PREFIXES.forEach { prefix ->
            if (stripped.startsWith(prefix) && stripped.length - prefix.length >= 2) {
                stripped = stripped.removePrefix(prefix)
            }
        }
        ORG_SUFFIXES.forEach { suffix ->
            if (stripped.endsWith(suffix) && stripped.length - suffix.length >= 2) {
                stripped = stripped.removeSuffix(suffix)
            }
        }

        val glyphs = stripped.filter { !it.isWhitespace() }
        if (glyphs.isEmpty()) return "?"
        val first = glyphs.first()
        if (first.code < 0x2E80) {
            val words = stripped.split(' ', '.', '-', '_', ',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (words.size >= 2) {
                return (words[0].first().toString() + words[1].first()).uppercase()
            }
            return glyphs.take(2).uppercase()
        }

        return glyphs.take(2)
    }

    private fun isNumeric(value: String): Boolean =
        value.isNotEmpty() && value.all { it.isDigit() || it in "+-() " }

    private fun resolveSize(imageView: ImageView): Int {
        val fromLayout = imageView.layoutParams?.width ?: 0
        if (fromLayout > 0) return fromLayout
        if (imageView.width > 0) return imageView.width
        return (38 * imageView.resources.displayMetrics.density).toInt()
    }
}
