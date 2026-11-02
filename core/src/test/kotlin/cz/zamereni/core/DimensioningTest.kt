package cz.zamereni.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DimensioningTest {
    private val wall = RectM(0.0, 0.0, 10.0, 6.0)
    private val model = FacadeModel(
        wall = wall,
        wallPolygon = listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 6.0), Pt(0.0, 6.0)),
        openings = listOf(RectM(1.0, 1.2, 1.2, 1.5), RectM(5.6, 3.8, 1.0, 2.2)),
    )
    private val dims = Dimensioning.generate(model)

    @Test
    fun `obrys ma sirku a vysku zdi`() {
        val o = dims.filter { it.layer == DimLayer.OUTLINE }.map { it.value }.sorted()
        assertEquals(listOf(6.0, 10.0), o)
    }

    @Test
    fun `kazdy otvor ma sirku a vysku`() {
        val o = dims.filter { it.layer == DimLayer.OPENINGS }.map { it.value }
        assertEquals(4, o.size)
        for (v in listOf(1.2, 1.5, 1.0, 2.2)) assertTrue(o.any { abs(it - v) < 1e-9 }, "chybí $v")
    }

    @Test
    fun `retezova kota dava dohromady sirku zdi`() {
        val chain = dims.filter { it.layer == DimLayer.POSITIONS && it.a.y == wall.bottom && it.b.y == wall.bottom }
        assertEquals(5, chain.size) // 0 | 1,0 | 2,2 | 5,6 | 6,6 | 10
        assertTrue(abs(chain.sumOf { it.value } - 10.0) < 1e-9)
    }

    @Test
    fun `parapet jen u oken, ne u dveri na terenu`() {
        val sills = dims.filter { it.layer == DimLayer.POSITIONS && it.a.x == it.b.x }
        assertEquals(1, sills.size)
        assertTrue(abs(sills.single().value - 3.3) < 1e-9)
    }
}
