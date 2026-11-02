package cz.zamereni.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cz.zamereni.core.Measurement
import cz.zamereni.core.Pt
import kotlin.math.min

private val targetColors = listOf(Color(0xFF1E88E5), Color(0xFFE53935), Color(0xFF43A047), Color(0xFF8E24AA))

private data class Segment(val a: Pt, val b: Pt, var calIndex: Int)

/**
 * Režim A: na snímku se zobrazí nalezené terče, uživatel klepnutím zadá dvojice bodů
 * a aplikace spočítá jejich vzdálenost v rovině přiřazeného terče.
 * Ovládání: klepnutí = bod, dvěma prsty = zoom a posun.
 */
@Composable
fun MeasureScreen(bitmap: Bitmap, detection: TargetDetector.Result, onBack: () -> Unit) {
    val context = LocalContext.current
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val cals = detection.calibrations
    val segments = remember { mutableStateListOf<Segment>() }
    var pending by remember { mutableStateOf<Pt?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    fun fitScale() = if (canvasSize == Size.Zero) 1f
    else min(canvasSize.width / bitmap.width, canvasSize.height / bitmap.height)

    fun scale() = fitScale() * zoom
    fun origin() = Offset(
        (canvasSize.width - bitmap.width * scale()) / 2f + pan.x,
        (canvasSize.height - bitmap.height * scale()) / 2f + pan.y,
    )
    fun toImage(o: Offset) = (o - origin()).let { Pt(it.x / scale().toDouble(), it.y / scale().toDouble()) }
    fun toScreen(p: Pt) = origin() + Offset(p.x.toFloat() * scale(), p.y.toFloat() * scale())

    fun nearestCal(mid: Pt): Int = cals.indices.minByOrNull { i ->
        val pts = cals[i].target.imagePoints
        Pt(pts.sumOf { it.x } / pts.size, pts.sumOf { it.y } / pts.size).dist(mid)
    } ?: -1

    val results: List<Measurement?> = segments.map { s ->
        if (s.calIndex in cals.indices) cals[s.calIndex].measure(s.a, s.b) else null
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, p, z, _ ->
                            zoom = (zoom * z).coerceIn(1f, 20f); pan += p
                        }
                    }
                    .pointerInput(cals) {
                        detectTapGestures { o ->
                            val p = toImage(o)
                            val first = pending
                            if (first == null) pending = p
                            else {
                                val mid = Pt((first.x + p.x) / 2, (first.y + p.y) / 2)
                                segments += Segment(first, p, nearestCal(mid)); pending = null
                            }
                        }
                    },
            ) {
                canvasSize = size
                val o = origin()
                drawImage(
                    image,
                    dstOffset = IntOffset(o.x.toInt(), o.y.toInt()),
                    dstSize = IntSize((bitmap.width * scale()).toInt(), (bitmap.height * scale()).toInt()),
                )
                cals.forEachIndexed { i, cal ->
                    cal.target.imagePoints.forEach { drawCircle(targetColors[i % 4], 4f, toScreen(it)) }
                }
                segments.forEach { s ->
                    val c = if (s.calIndex >= 0) targetColors[s.calIndex % 4] else Color.Gray
                    drawLine(c, toScreen(s.a), toScreen(s.b), strokeWidth = 4f)
                    marker(toScreen(s.a), c); marker(toScreen(s.b), c)
                }
                pending?.let { marker(toScreen(it), Color.Yellow) }
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).padding(8.dp)) {
            detection.messages.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                itemsIndexed(segments) { i, s ->
                    val m = results.getOrNull(i)
                    val name = cals.getOrNull(s.calIndex)?.target?.spec?.name ?: "bez terče"
                    val text = if (m == null) "Úsek ${i + 1}: není k dispozici terč"
                    else "Úsek ${i + 1}: %.3f m ± %.3f m (rovina %s)%s".format(
                        m.lengthM, m.sigmaM, name,
                        if (m.isExtrapolated) "  ⚠ daleko od terče (%.0f×)".format(m.extrapolation) else "",
                    )
                    Text(text, Modifier.fillMaxWidth().clickable {
                        // klepnutím na řádek se úsek přiřadí k dalšímu terči na snímku
                        if (cals.isNotEmpty()) {
                            segments[i] = s.copy(calIndex = (s.calIndex + 1) % cals.size)
                        }
                    }.padding(vertical = 4.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) { Text("Zpět") }
                OutlinedButton(onClick = {
                    if (pending != null) pending = null else if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                }) { Text("Smazat poslední") }
                Button(onClick = {
                    val body = buildString {
                        appendLine("Zaměření – režim A")
                        detection.messages.forEach { appendLine(it) }
                        segments.forEachIndexed { i, _ ->
                            results[i]?.let { appendLine("Úsek ${i + 1}: %.3f m ± %.3f m".format(it.lengthM, it.sigmaM)) }
                        }
                    }
                    context.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body), "Sdílet výsledky",
                    ))
                }) { Text("Sdílet") }
            }
        }
    }
}

private fun DrawScope.marker(p: Offset, c: Color) {
    drawCircle(Color.White, 9f, p)
    drawCircle(c, 6f, p)
}
