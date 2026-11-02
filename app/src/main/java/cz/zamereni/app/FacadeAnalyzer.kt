package cz.zamereni.app

import android.graphics.Bitmap
import cz.zamereni.core.FacadeModel
import cz.zamereni.core.PlaneCalibration
import cz.zamereni.core.Pt
import cz.zamereni.core.RectM
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Narovná snímek do roviny terče (pohled jako na výkrese) a najde obrys zdi a otvory.
 * Referenční implementace a testovací data: tools/auto_dims.py, tools/synth_facade.py.
 */
object FacadeAnalyzer {
    /** Rozlišení narovnaného pohledu [px/m]. */
    const val S = 100.0
    /** Narovnává se nejvýš do této vzdálenosti od terče [m]. */
    private const val MAX_HALF = 15.0
    private const val MAX_PX = 3000

    class Result(
        /** Narovnaný pohled; pixel (u, v) odpovídá bodu roviny [lo] + (u, v) / [S] metrů. */
        val ortho: Bitmap,
        val lo: Pt,
        val model: FacadeModel?,
        val message: String,
    ) {
        fun toPlane(u: Double, v: Double) = Pt(lo.x + u / S, lo.y + v / S)
        fun toOrtho(p: Pt) = Pt((p.x - lo.x) * S, (p.y - lo.y) * S)
    }

    fun analyze(bitmap: Bitmap, cal: PlaneCalibration): Result {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()

        // Rozsah narovnání: rohy snímku promítnuté do roviny, oříznuté kolem terče
        val spec = cal.target.spec
        val c = spec.center
        val imgCorners = listOf(Pt(0.0, 0.0), Pt(bitmap.width.toDouble(), 0.0),
            Pt(bitmap.width.toDouble(), bitmap.height.toDouble()), Pt(0.0, bitmap.height.toDouble()))
            .map(cal::toPlane)
        var loX = max(imgCorners.minOf { it.x }, c.x - MAX_HALF)
        var loY = max(imgCorners.minOf { it.y }, c.y - MAX_HALF)
        var hiX = min(imgCorners.maxOf { it.x }, c.x + MAX_HALF)
        var hiY = min(imgCorners.maxOf { it.y }, c.y + MAX_HALF)
        if (hiX <= loX || hiY <= loY) { loX = c.x - 5; loY = c.y - 5; hiX = c.x + 5; hiY = c.y + 5 }
        val w = min(ceil((hiX - loX) * S).toInt(), MAX_PX)
        val h = min(ceil((hiY - loY) * S).toInt(), MAX_PX)
        val lo = Pt(loX, loY)

        // M = A · H, kde A převádí metry na pixely narovnaného pohledu
        val hm = cal.imageToPlane.h
        val a = doubleArrayOf(S, 0.0, -loX * S, 0.0, S, -loY * S, 0.0, 0.0, 1.0)
        val m = DoubleArray(9) { k -> (0 until 3).sumOf { a[(k / 3) * 3 + it] * hm[it * 3 + k % 3] } }
        val mMat = Mat(3, 3, CvType.CV_64F).apply { put(0, 0, *m) }

        val ortho = Mat()
        Imgproc.warpPerspective(gray, ortho, mMat, Size(w.toDouble(), h.toDouble()),
            Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0))
        val ones = Mat(gray.size(), CvType.CV_8U, Scalar(255.0))
        val valid = Mat()
        Imgproc.warpPerspective(ones, valid, mMat, Size(w.toDouble(), h.toDouble()),
            Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar(0.0))
        ones.release(); gray.release()

        val boardW = spec.cols * spec.squareM
        val boardH = spec.rows * spec.squareM
        val wallMask = wallMask(ortho, valid, lo, boardW, boardH)
        val (outer, holes) = wallHoles(wallMask, lo, boardW, boardH)
        val dark = darkOpenings(ortho, valid, lo, boardW, boardH)
        val openings = (holes + dark.filter { d -> holes.none { overlaps(it, d) } }).sortedBy { it.x }

        val model: FacadeModel?
        val message: String
        if (outer == null) {
            model = null
            message = "Obrys zdi se nepodařilo najít. Terč musí být opřený o zeď a zeď vidět celá."
        } else {
            val r = Imgproc.boundingRect(outer)
            val wall = RectM(lo.x + r.x / S, lo.y + r.y / S, r.width / S, r.height / S)
            val c2f = MatOfPoint2f(*outer.toArray())
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, 0.05 * S, true)
            val poly = approx.toArray().map { Pt(lo.x + it.x / S, lo.y + it.y / S) }
            model = FacadeModel(wall, poly, openings)
            message = "Zeď %.2f × %.2f m, otvorů: %d".format(wall.w, wall.h, openings.size)
        }

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(ortho, bmp)
        ortho.release(); valid.release(); wallMask.release(); mMat.release()
        return Result(bmp, lo, model, message)
    }

    /** Plocha zdi: souvislá oblast bez hran kolem terče (terč je opřený o zeď). */
    private fun wallMask(ortho: Mat, valid: Mat, lo: Pt, boardW: Double, boardH: Double): Mat {
        val blur = Mat()
        Imgproc.GaussianBlur(ortho, blur, Size(9.0, 9.0), 0.0)
        val edges = Mat()
        Imgproc.Canny(blur, edges, 10.0, 30.0)
        val k5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.dilate(edges, edges, k5)

        val w = ortho.cols(); val h = ortho.rows()
        val inv = Mat()
        Core.bitwise_not(valid, inv)
        val barrier = Mat()
        Core.bitwise_or(edges, inv, barrier)
        Imgproc.threshold(barrier, barrier, 0.0, 1.0, Imgproc.THRESH_BINARY)
        val mask = Mat.zeros(h + 2, w + 2, CvType.CV_8U)
        barrier.copyTo(mask.submat(1, h + 1, 1, w + 1))

        val off = 0.2
        val seeds = listOf(
            Pt(-off, boardH / 2), Pt(boardW + off, boardH / 2),
            Pt(boardW / 2, -off), Pt(boardW / 2, boardH + off),
        )
        val flags = 4 or Imgproc.FLOODFILL_MASK_ONLY or (255 shl 8)
        for (s in seeds) {
            val u = ((s.x - lo.x) * S).toInt(); val v = ((s.y - lo.y) * S).toInt()
            if (u in 0 until w && v in 0 until h && mask.get(v + 1, u + 1)[0] == 0.0) {
                Imgproc.floodFill(blur, mask, Point(u.toDouble(), v.toDouble()), Scalar(0.0), Rect(),
                    Scalar(255.0), Scalar(255.0), flags)
            }
        }
        val out = Mat()
        Core.inRange(mask.submat(1, h + 1, 1, w + 1), Scalar(255.0), Scalar(255.0), out)
        Imgproc.dilate(out, out, k5)
        listOf(blur, edges, inv, barrier, mask, k5).forEach { it.release() }
        return out
    }

    /** Vnější obrys zdi a díry v ní (okna včetně rámu). */
    private fun wallHoles(mask: Mat, lo: Pt, boardW: Double, boardH: Double): Pair<MatOfPoint?, List<RectM>> {
        val contours = ArrayList<MatOfPoint>()
        val hier = Mat()
        Imgproc.findContours(mask.clone(), contours, hier, Imgproc.RETR_CCOMP, Imgproc.CHAIN_APPROX_SIMPLE)
        if (contours.isEmpty()) return null to emptyList()
        val parent = { i: Int -> hier.get(0, i)[3].toInt() }
        val outerIdx = contours.indices.filter { parent(it) < 0 }.maxByOrNull { Imgproc.contourArea(contours[it]) }
            ?: return null to emptyList()
        if (Imgproc.contourArea(contours[outerIdx]) < 1.0 * S * S) return null to emptyList()
        val holes = contours.indices.filter { parent(it) == outerIdx }.mapNotNull { i ->
            toOpening(Imgproc.boundingRect(contours[i]), lo, boardW, boardH)
        }
        return contours[outerIdx] to holes
    }

    /** Tmavé obdélníky (sklo, dveře) – doplní otvory na okraji zdi, které nejsou dírou. */
    private fun darkOpenings(ortho: Mat, valid: Mat, lo: Pt, boardW: Double, boardH: Double): List<RectM> {
        val blur = Mat()
        Imgproc.GaussianBlur(ortho, blur, Size(5.0, 5.0), 0.0)
        val bw = Mat()
        val block = (1.5 * S).toInt() or 1
        Imgproc.adaptiveThreshold(blur, bw, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, block, 15.0)
        Core.bitwise_and(bw, valid, bw)
        Imgproc.morphologyEx(bw, bw, Imgproc.MORPH_OPEN,
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)))
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(bw, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        blur.release(); bw.release()
        return contours.mapNotNull { c ->
            val r = Imgproc.boundingRect(c)
            if (Imgproc.contourArea(c) < 0.85 * r.width * r.height) null else toOpening(r, lo, boardW, boardH)
        }
    }

    private fun toOpening(r: Rect, lo: Pt, boardW: Double, boardH: Double): RectM? {
        val o = RectM(lo.x + r.x / S, lo.y + r.y / S, r.width / S, r.height / S)
        if (o.w !in 0.4..3.5 || o.h !in 0.4..3.5) return null
        val board = RectM(-0.1, -0.1, boardW + 0.2, boardH + 0.2)
        return if (overlaps(o, board)) null else o
    }

    private fun overlaps(a: RectM, b: RectM) =
        !(a.right < b.x || b.right < a.x || a.bottom < b.y || b.bottom < a.y)
}
