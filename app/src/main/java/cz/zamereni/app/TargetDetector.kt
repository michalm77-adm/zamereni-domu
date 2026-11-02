package cz.zamereni.app

import android.graphics.Bitmap
import cz.zamereni.core.DetectedTarget
import cz.zamereni.core.PlaneCalibration
import cz.zamereni.core.Pt
import cz.zamereni.core.TargetSpec
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.CharucoBoard
import org.opencv.objdetect.CharucoDetector
import org.opencv.objdetect.Objdetect

/** Najde na snímku všechny známé ChArUco terče a spočítá pro každý kalibraci roviny. */
object TargetDetector {

    private val dictionary by lazy { Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_100) }

    private val detectors: Map<TargetSpec, CharucoDetector> by lazy {
        TargetSpec.ALL.associateWith { spec ->
            val ids = MatOfInt(*spec.markerIds.toIntArray())
            val board = CharucoBoard(
                Size(spec.cols.toDouble(), spec.rows.toDouble()),
                spec.squareM.toFloat(), spec.markerM.toFloat(), dictionary, ids,
            )
            CharucoDetector(board)
        }
    }

    data class Result(val calibrations: List<PlaneCalibration>, val messages: List<String>)

    fun detect(bitmap: Bitmap): Result {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()

        val calibrations = mutableListOf<PlaneCalibration>()
        val messages = mutableListOf<String>()
        for ((spec, detector) in detectors) {
            val corners = Mat()
            val ids = Mat()
            detector.detectBoard(gray, corners, ids)
            val n = ids.rows()
            if (n > 0) {
                val idList = (0 until n).map { ids.get(it, 0)[0].toInt() }
                val pts = (0 until n).map { corners.get(it, 0).let { c -> Pt(c[0], c[1]) } }
                if (n >= PlaneCalibration.MIN_CORNERS) {
                    val cal = PlaneCalibration.from(DetectedTarget(spec, idList, pts))
                    calibrations += cal
                    messages += "Terč ${spec.name}: $n/${spec.innerCornerCount} rohů, RMS %.2f px".format(cal.rmsPx)
                } else {
                    messages += "Terč ${spec.name}: jen $n rohů, přibližte se nebo terč narovnejte"
                }
            }
            corners.release(); ids.release()
        }
        gray.release()
        if (calibrations.isEmpty() && messages.isEmpty()) messages += "Na snímku nebyl nalezen žádný terč"
        return Result(calibrations, messages)
    }
}
