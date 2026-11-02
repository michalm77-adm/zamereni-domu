package cz.zamereni.app

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.zamereni.core.DimLayer
import cz.zamereni.core.Dimension
import cz.zamereni.core.Dimensioning
import cz.zamereni.core.PlaneCalibration
import cz.zamereni.core.Pt
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

private val layerColors = mapOf(
    DimLayer.OUTLINE to Color(0xFF1565C0),
    DimLayer.OPENINGS to Color(0xFFD84315),
    DimLayer.POSITIONS to Color(0xFF2E7D32),
    DimLayer.MANUAL to Color(0xFF6A1B9A),
)

/** Ruční kóta včetně nejistoty. */
private data class ManualDim(val dim: Dimension, val sigma: Double, val extrapolated: Boolean)

/**
 * Narovnaný pohled na fasádu s automatickými kótami ve vrstvách, které lze vypínat.
 * Klepnutím na dva body se přidá ruční kóta (vrstva „Ruční“).
 */
@Composable
fun FacadeScreen(
    facade: FacadeAnalyzer.Result,
    cal: PlaneCalibration,
    detectionMessages: List<String>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val image = remember(facade) { facade.ortho.asImageBitmap() }
    val autoDims = remember(facade) { facade.model?.let(Dimensioning::generate) ?: emptyList() }
    val visible = remember { mutableStateMapOf<DimLayer, Boolean>().apply { DimLayer.entries.forEach { put(it, true) } } }
    val manual = remember { mutableStateListOf<ManualDim>() }
    var pending by remember { mutableStateOf<Pt?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    val textMeasurer = rememberTextMeasurer()
    val toImage = remember(cal) { cal.imageToPlane.inverse() }

    val bw = facade.ortho.width; val bh = facade.ortho.height
    fun scale() = if (canvasSize == Size.Zero) 1f else min(canvasSize.width / bw, canvasSize.height / bh) * zoom
    fun origin() = Offset((canvasSize.width - bw * scale()) / 2f + pan.x, (canvasSize.height - bh * scale()) / 2f + pan.y)
    fun planeToScreen(p: Pt): Offset {
        val o = facade.toOrtho(p)
        return origin() + Offset(o.x.toFloat() * scale(), o.y.toFloat() * scale())
    }
    fun screenToPlane(o: Offset): Pt {
        val d = o - origin()
        return facade.toPlane(d.x / scale().toDouble(), d.y / scale().toDouble())
    }

    fun onTap(o: Offset) {
        val p = screenToPlane(o)
        val first = pending
        if (first == null) { pending = p; return }
        // měření s nejistotou: body se převedou zpět do pixelů původního snímku
        val m = cal.measure(toImage.apply(first), toImage.apply(p))
        val horizontal = abs(p.x - first.x) >= abs(p.y - first.y)
        val off = if (horizontal) Pt(0.0, -0.25) else Pt(0.25, 0.0)
        manual += ManualDim(Dimension(DimLayer.MANUAL, first, p, off), m.sigmaM, m.isExtrapolated)
        pending = null
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DimLayer.entries.forEach { layer ->
                FilterChip(
                    selected = visible[layer] == true,
                    onClick = { visible[layer] = visible[layer] != true },
                    label = { Text(layer.label) },
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var moved = false
                        do {
                            val ev = awaitPointerEvent()
                            val far = (ev.changes.first().position - down.position).getDistance() > viewConfiguration.touchSlop
                            if (ev.changes.size > 1 || far) moved = true
                            if (moved) {
                                zoom = (zoom * ev.calculateZoom()).coerceIn(1f, 20f)
                                pan += ev.calculatePan()
                                ev.changes.forEach { it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                        if (!moved) onTap(down.position)
                    }
                },
            ) {
                canvasSize = size
                val o = origin()
                drawImage(image, dstOffset = IntOffset(o.x.toInt(), o.y.toInt()),
                    dstSize = IntSize((bw * scale()).toInt(), (bh * scale()).toInt()))

                facade.model?.let { model ->
                    if (visible[DimLayer.OUTLINE] == true && model.wallPolygon.size >= 3) {
                        val path = Path().apply {
                            model.wallPolygon.forEachIndexed { i, p ->
                                val s = planeToScreen(p); if (i == 0) moveTo(s.x, s.y) else lineTo(s.x, s.y)
                            }
                            close()
                        }
                        drawPath(path, layerColors.getValue(DimLayer.OUTLINE), style = Stroke(3f))
                    }
                    if (visible[DimLayer.OPENINGS] == true) {
                        model.openings.forEach { r ->
                            val a = planeToScreen(Pt(r.x, r.y)); val b = planeToScreen(Pt(r.right, r.bottom))
                            drawRect(layerColors.getValue(DimLayer.OPENINGS), a, androidx.compose.ui.geometry.Size(b.x - a.x, b.y - a.y), style = Stroke(3f))
                        }
                    }
                }
                autoDims.filter { visible[it.layer] == true }.forEach {
                    drawDimension(it, null, layerColors.getValue(it.layer), textMeasurer, ::planeToScreen)
                }
                if (visible[DimLayer.MANUAL] == true) manual.forEach {
                    drawDimension(it.dim, it.sigma, layerColors.getValue(DimLayer.MANUAL), textMeasurer, ::planeToScreen)
                }
                pending?.let { drawCircle(Color.Yellow, 12f, planeToScreen(it)) }
            }
        }
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Text(facade.message, style = MaterialTheme.typography.titleSmall)
            detectionMessages.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                if (pending == null) "Ruční kóta: klepněte na začátek úseku. Dvěma prsty zoom a posun."
                else "Ruční kóta: klepněte na konec úseku.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                OutlinedButton(onClick = onBack) { Text("Zpět") }
                OutlinedButton(onClick = {
                    if (pending != null) pending = null else if (manual.isNotEmpty()) manual.removeAt(manual.lastIndex)
                }) { Text("Smazat ruční") }
                Button(onClick = {
                    val body = buildString {
                        appendLine("Zaměření fasády – režim A")
                        appendLine(facade.message)
                        facade.model?.let { m ->
                            m.openings.forEachIndexed { i, r ->
                                appendLine("Otvor ${i + 1}: %.2f × %.2f m, od levého rohu %.2f m, nad spodní hranou %.2f m"
                                    .format(r.w, r.h, r.x - m.wall.x, m.wall.bottom - r.bottom))
                            }
                        }
                        manual.forEachIndexed { i, d ->
                            appendLine("Ruční kóta ${i + 1}: %.3f m ± %.3f m".format(d.dim.value, d.sigma))
                        }
                    }
                    context.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body), "Sdílet kóty"))
                }) { Text("Sdílet") }
            }
        }
    }
}

/** Kóta jako na výkrese: vynášecí čáry, kótovací čára s čárkami na koncích a hodnota. */
private fun DrawScope.drawDimension(
    d: Dimension,
    sigma: Double?,
    color: Color,
    tm: TextMeasurer,
    toScreen: (Pt) -> Offset,
) {
    val a = toScreen(d.a); val b = toScreen(d.b)
    val a2 = toScreen(d.a + d.offset); val b2 = toScreen(d.b + d.offset)
    drawLine(color.copy(alpha = 0.6f), a, a2, strokeWidth = 1.5f)
    drawLine(color.copy(alpha = 0.6f), b, b2, strokeWidth = 1.5f)
    drawLine(color, a2, b2, strokeWidth = 2.5f)
    val dx = b2.x - a2.x; val dy = b2.y - a2.y
    val len = hypot(dx, dy).coerceAtLeast(1f)
    val ux = dx / len; val uy = dy / len
    val tick = 10f
    for (p in listOf(a2, b2)) {
        drawLine(color, p - Offset((ux - uy) * tick, (uy + ux) * tick), p + Offset((ux - uy) * tick, (uy + ux) * tick), 2.5f)
    }
    if (len < 30f) return
    val label = if (sigma == null) "%.2f".format(d.value) else "%.2f ±%.2f".format(d.value, sigma)
    val layout = tm.measure(label, TextStyle(color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        background = Color.White.copy(alpha = 0.75f)))
    val mid = Offset((a2.x + b2.x) / 2, (a2.y + b2.y) / 2)
    val angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
    val upright = if (angle > 90f || angle < -90f) angle + 180f else angle
    rotate(upright, mid) {
        drawText(layout, topLeft = mid - Offset(layout.size.width / 2f, layout.size.height + 2f))
    }
}
