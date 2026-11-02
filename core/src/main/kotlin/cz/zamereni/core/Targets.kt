package cz.zamereni.core

/**
 * Definice ChArUco terče. Musí odpovídat vytištěným PDF (viz terce/terce_spec.json).
 * Všechny terče používají slovník DICT_4X4_100 a vzor OpenCV CharucoBoard (legacyPattern = false).
 */
data class TargetSpec(
    val name: String,
    val format: String,
    val cols: Int,
    val rows: Int,
    /** Strana pole šachovnice v metrech (po výrobě nahradit skutečně změřenou hodnotou). */
    val squareM: Double,
    val markerM: Double,
    val firstMarkerId: Int,
) {
    val markerCount get() = cols * rows / 2
    val markerIds get() = (firstMarkerId until firstMarkerId + markerCount).toList()
    val innerCornerCount get() = (cols - 1) * (rows - 1)

    /**
     * Poloha vnitřního rohu šachovnice v rovině terče [m] podle jeho ChArUco ID.
     * Číslování odpovídá OpenCV: id = řádek * (cols - 1) + sloupec, počátek v levém horním rohu desky, osa y dolů.
     */
    fun cornerPosition(charucoId: Int): Pt {
        require(charucoId in 0 until innerCornerCount) { "Neplatné ID rohu $charucoId pro terč $name" }
        val c = charucoId % (cols - 1)
        val r = charucoId / (cols - 1)
        return Pt((c + 1) * squareM, (r + 1) * squareM)
    }

    /** Střed šachovnice v rovině terče [m]. */
    val center get() = Pt(cols * squareM / 2, rows * squareM / 2)

    /** Polovina úhlopříčky oblasti vnitřních rohů [m]; slouží k posouzení extrapolace. */
    val halfSpan get() = Pt(0.0, 0.0).dist(Pt((cols - 2) * squareM, (rows - 2) * squareM)) / 2

    companion object {
        val T1 = TargetSpec("T1", "1x1m", 5, 5, 0.180, 0.135, 0)
        val T2 = TargetSpec("T2", "1x1m", 5, 5, 0.180, 0.135, 12)
        val T3 = TargetSpec("T3", "1x1m", 5, 5, 0.180, 0.135, 24)
        val A1 = TargetSpec("A1", "A4", 4, 6, 0.042, 0.0315, 36)
        val A2 = TargetSpec("A2", "A4", 4, 6, 0.042, 0.0315, 48)
        val A3 = TargetSpec("A3", "A4", 4, 6, 0.042, 0.0315, 60)
        val ALL = listOf(T1, T2, T3, A1, A2, A3)

        /** Najde terč podle ID markeru (každý terč má vlastní rozsah ID). */
        fun byMarkerId(id: Int) = ALL.firstOrNull { id in it.markerIds }
    }
}
