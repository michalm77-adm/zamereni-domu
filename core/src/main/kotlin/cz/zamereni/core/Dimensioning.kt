package cz.zamereni.core

/** Obdélník v rovině fasády [m]; osa x doprava, y dolů. */
data class RectM(val x: Double, val y: Double, val w: Double, val h: Double) {
    val right get() = x + w
    val bottom get() = y + h
}

/** Automaticky rozpoznaná fasáda v rovině terče [m]. */
data class FacadeModel(
    /** Obdélník ohraničující zeď. */
    val wall: RectM,
    /** Obrys zdi jako mnohoúhelník (u štítu pětiúhelník). */
    val wallPolygon: List<Pt>,
    /** Okna a dveře. */
    val openings: List<RectM>,
)

/** Vrstvy kót, které lze v aplikaci vypínat. */
enum class DimLayer(val label: String) {
    OUTLINE("Obrys"),
    OPENINGS("Otvory"),
    POSITIONS("Polohy otvorů"),
    MANUAL("Ruční"),
}

/**
 * Kóta: měřená úsečka [a]–[b] [m], vynesená posunutá o [offset] [m] (kolmo k úsečce).
 */
data class Dimension(
    val layer: DimLayer,
    val a: Pt,
    val b: Pt,
    val offset: Pt,
) {
    val value get() = a.dist(b)
}

/** Vytvoří kóty z modelu fasády, jako na výkresu pohledu. */
object Dimensioning {
    /** Odstup kótovacích čar od zdi / otvoru [m]. */
    const val GAP = 0.35

    fun generate(model: FacadeModel): List<Dimension> {
        val w = model.wall
        val out = mutableListOf<Dimension>()

        // Obrys: celková šířka pod zdí, celková výška vlevo
        out += Dimension(DimLayer.OUTLINE, Pt(w.x, w.bottom), Pt(w.right, w.bottom), Pt(0.0, 2 * GAP))
        out += Dimension(DimLayer.OUTLINE, Pt(w.x, w.y), Pt(w.x, w.bottom), Pt(-2 * GAP, 0.0))

        // Otvory: šířka nad otvorem, výška vpravo od otvoru
        for (o in model.openings) {
            out += Dimension(DimLayer.OPENINGS, Pt(o.x, o.y), Pt(o.right, o.y), Pt(0.0, -GAP / 2))
            out += Dimension(DimLayer.OPENINGS, Pt(o.right, o.y), Pt(o.right, o.bottom), Pt(GAP / 2, 0.0))
        }

        // Polohy: řetězová kóta pod zdí (od levého rohu přes hrany otvorů k pravému rohu)
        // a výška parapetu / prahu každého otvoru nad spodní hranou zdi.
        val xs = (listOf(w.x, w.right) + model.openings.flatMap { listOf(it.x, it.right) })
            .filter { it >= w.x - 1e-6 && it <= w.right + 1e-6 }
            .sorted()
            .fold(mutableListOf<Double>()) { acc, x -> if (acc.isEmpty() || x - acc.last() > 0.02) acc += x; acc }
        for (i in 0 until xs.size - 1) {
            out += Dimension(DimLayer.POSITIONS, Pt(xs[i], w.bottom), Pt(xs[i + 1], w.bottom), Pt(0.0, GAP))
        }
        for (o in model.openings) {
            if (w.bottom - o.bottom > 0.05) {
                out += Dimension(DimLayer.POSITIONS, Pt(o.x, o.bottom), Pt(o.x, w.bottom), Pt(-GAP / 2, 0.0))
            }
        }
        return out
    }
}
