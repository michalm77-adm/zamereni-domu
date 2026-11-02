package cz.zamereni.core

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/** Rohy jednoho terče nalezené na snímku (výstup detektoru ChArUco). */
data class DetectedTarget(
    val spec: TargetSpec,
    val charucoIds: List<Int>,
    /** Pixelové souřadnice rohů ve stejném pořadí jako [charucoIds]. */
    val imagePoints: List<Pt>,
) {
    init { require(charucoIds.size == imagePoints.size) }
}

/** Výsledek měření délky v rovině terče. */
data class Measurement(
    val lengthM: Double,
    /** Směrodatná odchylka odhadu [m] (Monte Carlo: šum rohů + nepřesnost kliknutí). */
    val sigmaM: Double,
    /** Kolikrát dál od středu terče leží měřené body oproti velikosti terče (> 3 = pozor, extrapolace). */
    val extrapolation: Double,
) {
    val isExtrapolated get() = extrapolation > 3.0
}

/**
 * Kalibrace roviny terče: homografie pixely → metry v rovině terče.
 * Platí jen pro body, které leží ve stejné rovině jako terč.
 */
class PlaneCalibration private constructor(
    val target: DetectedTarget,
    val imageToPlane: Homography,
    /** RMS reprojekční chyba rohů v pixelech. */
    val rmsPx: Double,
) {
    fun toPlane(px: Pt): Pt = imageToPlane.apply(px)

    /** Metry na pixel v daném místě snímku (užitečné pro kontrolu rozlišení). */
    fun metersPerPixel(px: Pt) = imageToPlane.localScale(px)

    fun distance(a: Pt, b: Pt) = toPlane(a).dist(toPlane(b))

    /**
     * Délka mezi dvěma pixely včetně odhadu nejistoty.
     * @param clickSigmaPx nepřesnost umístění bodu uživatelem [px]
     */
    fun measure(a: Pt, b: Pt, clickSigmaPx: Double = 1.0, iterations: Int = 300, seed: Int = 7): Measurement {
        val length = distance(a, b)
        val rnd = Random(seed)
        val cornerSigma = max(rmsPx, 0.3)
        val planePts = target.charucoIds.map { target.spec.cornerPosition(it) }
        var sum = 0.0; var sum2 = 0.0
        repeat(iterations) {
            val noisy = target.imagePoints.map { it + gauss(rnd, cornerSigma) }
            val h = Homography.fit(noisy, planePts)
            val d = h.apply(a + gauss(rnd, clickSigmaPx)).dist(h.apply(b + gauss(rnd, clickSigmaPx)))
            sum += d; sum2 += d * d
        }
        val mean = sum / iterations
        val sigma = sqrt(max(sum2 / iterations - mean * mean, 0.0))
        val c = target.spec.center
        val extra = max(toPlane(a).dist(c), toPlane(b).dist(c)) / target.spec.halfSpan
        return Measurement(length, sigma, extra)
    }

    companion object {
        /** Minimální počet rohů pro spolehlivou homografii. */
        const val MIN_CORNERS = 6

        fun from(target: DetectedTarget): PlaneCalibration {
            require(target.charucoIds.size >= MIN_CORNERS) {
                "Terč ${target.spec.name}: nalezeno jen ${target.charucoIds.size} rohů, potřeba aspoň $MIN_CORNERS"
            }
            val planePts = target.charucoIds.map { target.spec.cornerPosition(it) }
            val h = Homography.fit(target.imagePoints, planePts)
            val back = h.inverse()
            val rms = sqrt(planePts.indices.sumOf {
                val r = back.apply(planePts[it]).dist(target.imagePoints[it]); r * r
            } / planePts.size)
            return PlaneCalibration(target, h, rms)
        }

        private fun gauss(rnd: Random, sigma: Double): Pt {
            // Box–Muller
            val u1 = rnd.nextDouble().coerceAtLeast(1e-12); val u2 = rnd.nextDouble()
            val r = sqrt(-2 * ln(u1)) * sigma
            val t = 2 * Math.PI * u2
            return Pt(r * cos(t), r * kotlin.math.sin(t))
        }
    }
}
