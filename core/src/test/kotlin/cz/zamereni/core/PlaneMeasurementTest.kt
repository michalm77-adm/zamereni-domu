package cz.zamereni.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaneMeasurementTest {

    @Test
    fun `homografie z presnych bodu se vrati presne`() {
        val truth = Homography(doubleArrayOf(1.2, 0.1, 30.0, -0.05, 0.9, 12.0, 1e-4, -2e-4, 1.0))
        val src = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 80.0), Pt(0.0, 80.0), Pt(50.0, 40.0), Pt(20.0, 70.0))
        val h = Homography.fit(src, src.map(truth::apply))
        for (p in src + Pt(400.0, -300.0)) {
            val e = h.apply(p).dist(truth.apply(p))
            assertTrue(e < 1e-6, "odchylka $e v bodě $p")
        }
        val back = h.inverse().apply(h.apply(Pt(33.0, 44.0)))
        assertTrue(back.dist(Pt(33.0, 44.0)) < 1e-6)
    }

    @Test
    fun `cislovani rohu odpovida OpenCV`() {
        val t = TargetSpec.T1
        assertEquals(16, t.innerCornerCount)
        assertEquals(Pt(0.18, 0.18), t.cornerPosition(0))
        assertEquals(Pt(0.72, 0.18), t.cornerPosition(3))
        assertEquals(Pt(0.18, 0.36), t.cornerPosition(4))
        assertEquals(TargetSpec.A2, TargetSpec.byMarkerId(50))
        assertEquals(15, TargetSpec.A1.innerCornerCount)
    }

    @Test
    fun `mereni na synteticke fotce fasady`() {
        val json = Json.parse(javaClass.getResource("/fixture_t1_fasada.json")!!.readText()) as Map<*, *>
        val ids = (json["charuco_ids"] as List<*>).map { (it as Double).toInt() }
        val pts = (json["image_points"] as List<*>).map { it.toPt() }
        val cal = PlaneCalibration.from(DetectedTarget(TargetSpec.T1, ids, pts))
        assertTrue(cal.rmsPx < 0.5, "RMS reprojekce ${cal.rmsPx} px")

        for (s in json["segments"] as List<*>) {
            s as Map<*, *>
            val m = cal.measure(s["a_px"].toPt(), s["b_px"].toPt())
            val truth = s["true_m"] as Double
            val err = abs(m.lengthM - truth)
            println("%-14s skutečnost %.3f m, změřeno %.3f m, chyba %.1f mm (%.2f %%), σ %.1f mm, extrapolace %.1f×"
                .format(s["name"], truth, m.lengthM, err * 1000, err / truth * 100, m.sigmaM * 1000, m.extrapolation))
            // Uvnitř terče musí být chyba pod 0,5 %. Daleko od terče chyba roste s extrapolací;
            // tam vyžadujeme, aby ji aplikace poznala (příznak extrapolace) a aby ležela v rámci 3σ odhadu.
            if (m.isExtrapolated) {
                assertTrue(err < 3 * m.sigmaM, "${s["name"]}: chyba $err m mimo odhad σ ${m.sigmaM} m")
            } else {
                assertTrue(err / truth < 0.005, "${s["name"]}: chyba ${err / truth * 100} %")
                assertTrue(err < 3 * m.sigmaM + 0.001, "${s["name"]}: chyba $err m mimo odhad σ ${m.sigmaM} m")
            }
        }
    }

    private fun Any?.toPt(): Pt = (this as List<*>).let { Pt(it[0] as Double, it[1] as Double) }
}

/** Minimální JSON parser pro testovací data (objekty, pole, čísla, řetězce). */
internal object Json {
    fun parse(s: String): Any? = P(s).value()

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (s[i]) {
                '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws()
                    if (s[i] == '}') { i++; return m }
                    while (true) { ws(); val k = str(); ws(); i++; m[k] = value(); ws()
                        if (s[i++] == '}') return m } }
                '[' -> { i++; val l = ArrayList<Any?>(); ws()
                    if (s[i] == ']') { i++; return l }
                    while (true) { l += value(); ws(); if (s[i++] == ']') return l } }
                '"' -> str()
                else -> { val st = i
                    while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
                    val t = s.substring(st, i)
                    if (t.isEmpty()) { val w = s.substring(i).takeWhile { it.isLetter() }; i += w.length
                        if (w == "null") null else w == "true" } else t.toDouble() }
            }
        }
        fun str(): String { i++; val st = i; while (s[i] != '"') i++; return s.substring(st, i++) }
    }
}
