package cz.zamereni.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** Bod v rovině (pixely snímku nebo metry v rovině terče). */
data class Pt(val x: Double, val y: Double) {
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    fun dist(o: Pt) = hypot(x - o.x, y - o.y)
}

/** Projektivní transformace 3×3 (řádkově), h[8] je normalizováno na 1. */
class Homography(val h: DoubleArray) {
    init { require(h.size == 9) }

    fun apply(p: Pt): Pt {
        val w = h[6] * p.x + h[7] * p.y + h[8]
        return Pt((h[0] * p.x + h[1] * p.y + h[2]) / w, (h[3] * p.x + h[4] * p.y + h[5]) / w)
    }

    /** Lokální měřítko v bodě p: kolik jednotek cíle odpovídá 1 jednotce zdroje (odmocnina z det. Jacobiho matice). */
    fun localScale(p: Pt): Double {
        val e = 1e-3
        val c = apply(p)
        val dx = apply(Pt(p.x + e, p.y)) - c
        val dy = apply(Pt(p.x, p.y + e)) - c
        return sqrt(abs(dx.x * dy.y - dx.y * dy.x)) / e
    }

    fun inverse(): Homography {
        val a = h
        val inv = doubleArrayOf(
            a[4] * a[8] - a[5] * a[7], a[2] * a[7] - a[1] * a[8], a[1] * a[5] - a[2] * a[4],
            a[5] * a[6] - a[3] * a[8], a[0] * a[8] - a[2] * a[6], a[2] * a[3] - a[0] * a[5],
            a[3] * a[7] - a[4] * a[6], a[1] * a[6] - a[0] * a[7], a[0] * a[4] - a[1] * a[3],
        )
        val s = inv[8]
        return Homography(DoubleArray(9) { inv[it] / s })
    }

    companion object {
        /**
         * Normalizovaná DLT: homografie src → dst z ≥ 4 odpovídajících bodů (nejmenší čtverce).
         * Řešení = vlastní vektor A^T A s nejmenším vlastním číslem.
         */
        fun fit(src: List<Pt>, dst: List<Pt>): Homography {
            require(src.size == dst.size && src.size >= 4) { "Potřeba aspoň 4 dvojice bodů" }
            val (ts, ns) = normalization(src)
            val (td, nd) = normalization(dst)
            val ata = Array(9) { DoubleArray(9) }
            for (i in src.indices) {
                val (x, y) = ns[i].let { it.x to it.y }
                val (u, v) = nd[i].let { it.x to it.y }
                val r1 = doubleArrayOf(-x, -y, -1.0, 0.0, 0.0, 0.0, u * x, u * y, u)
                val r2 = doubleArrayOf(0.0, 0.0, 0.0, -x, -y, -1.0, v * x, v * y, v)
                for (r in arrayOf(r1, r2)) for (a in 0 until 9) for (b in 0 until 9) ata[a][b] += r[a] * r[b]
            }
            val hn = smallestEigenvector(ata)
            // H = Td^-1 * Hn * Ts
            val m = mul(mul(invSimilarity(td), hn), ts)
            require(abs(m[8]) > 1e-12) { "Degenerovaná homografie" }
            return Homography(DoubleArray(9) { m[it] / m[8] })
        }

        private fun normalization(p: List<Pt>): Pair<DoubleArray, List<Pt>> {
            val cx = p.sumOf { it.x } / p.size
            val cy = p.sumOf { it.y } / p.size
            val md = p.sumOf { hypot(it.x - cx, it.y - cy) } / p.size
            val s = if (md > 0) sqrt(2.0) / md else 1.0
            val t = doubleArrayOf(s, 0.0, -s * cx, 0.0, s, -s * cy, 0.0, 0.0, 1.0)
            return t to p.map { Pt(s * (it.x - cx), s * (it.y - cy)) }
        }

        private fun invSimilarity(t: DoubleArray): DoubleArray {
            val s = t[0]
            return doubleArrayOf(1 / s, 0.0, -t[2] / s, 0.0, 1 / s, -t[5] / s, 0.0, 0.0, 1.0)
        }

        private fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { k ->
            val r = k / 3; val c = k % 3
            (0 until 3).sumOf { a[r * 3 + it] * b[it * 3 + c] }
        }

        /** Jacobiho metoda pro symetrickou matici; vrací vlastní vektor k nejmenšímu vlastnímu číslu. */
        internal fun smallestEigenvector(m: Array<DoubleArray>): DoubleArray {
            val n = m.size
            val a = Array(n) { m[it].copyOf() }
            val v = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
            repeat(100) {
                var off = 0.0
                for (p in 0 until n) for (q in p + 1 until n) off += a[p][q] * a[p][q]
                if (off < 1e-30) return@repeat
                for (p in 0 until n) for (q in p + 1 until n) {
                    if (abs(a[p][q]) < 1e-300) continue
                    val theta = (a[q][q] - a[p][p]) / (2 * a[p][q])
                    val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                    val c = 1 / sqrt(t * t + 1); val s = t * c
                    for (k in 0 until n) {
                        val akp = a[k][p]; val akq = a[k][q]
                        a[k][p] = c * akp - s * akq; a[k][q] = s * akp + c * akq
                    }
                    for (k in 0 until n) {
                        val apk = a[p][k]; val aqk = a[q][k]
                        a[p][k] = c * apk - s * aqk; a[q][k] = s * apk + c * aqk
                    }
                    for (k in 0 until n) {
                        val vkp = v[k][p]; val vkq = v[k][q]
                        v[k][p] = c * vkp - s * vkq; v[k][q] = s * vkp + c * vkq
                    }
                }
            }
            val idx = (0 until n).minBy { a[it][it] }
            return DoubleArray(n) { v[it][idx] }
        }
    }
}
