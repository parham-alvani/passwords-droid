package me.parham1995.passwords.ui.icon

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Lucide's own canvas and stroke, which every icon is drawn against. */
private const val VIEWPORT = 24f
private const val STROKE_WIDTH = 2f

val LocalLucide = staticCompositionLocalOf { LucideSet.EMPTY }

/** Loads the icon set once, off the main thread, and hands it to everything below. */
@Composable
fun ProvideLucide(content: @Composable () -> Unit) {
    val context: Context = LocalContext.current.applicationContext
    val set by produceState(LucideSet.EMPTY, context) { value = LucideSet.load(context) }
    CompositionLocalProvider(LocalLucide provides set, content = content)
}

/** A Lucide glyph by name; an unknown name draws nothing rather than failing. */
@Composable
fun LucideIcon(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    contentDescription: String? = null,
) {
    val slot =
        modifier.size(size).semantics {
            contentDescription?.let { this.contentDescription = it }
        }
    val glyph = LocalLucide.current.glyph(name)
    if (glyph == null) {
        Box(slot)
        return
    }
    Canvas(slot) {
        val factor = this.size.minDimension / VIEWPORT
        scale(factor, pivot = Offset.Zero) {
            // Scaling the canvas scales the stroke with it, which keeps Lucide's
            // 2px-of-24 weight whatever size the icon is drawn at.
            val stroke = Stroke(width = STROKE_WIDTH, cap = StrokeCap.Round, join = StrokeJoin.Round)
            glyph.stroke?.let { drawPath(it, tint, style = stroke) }
            glyph.fill?.let {
                drawPath(it, tint, style = Fill)
                drawPath(it, tint, style = stroke)
            }
        }
    }
}

/**
 * An Iconic colour name as a colour. Fixed hues rather than theme roles, so
 * a folder is the same blue on every device and in Daftar; the theme's own
 * tint when no colour was chosen.
 */
fun iconColor(
    name: String?,
    fallback: Color,
): Color =
    when (name?.lowercase()) {
        "red" -> Color(0xFFE5484D)
        "orange" -> Color(0xFFF76B15)
        "yellow" -> Color(0xFFE2A336)
        "green" -> Color(0xFF30A46C)
        "cyan" -> Color(0xFF00A2C7)
        "blue" -> Color(0xFF0090FF)
        "purple" -> Color(0xFF8E4EC6)
        "pink" -> Color(0xFFD6409F)
        "gray", "grey" -> Color(0xFF8B8D98)
        else -> fallback
    }
