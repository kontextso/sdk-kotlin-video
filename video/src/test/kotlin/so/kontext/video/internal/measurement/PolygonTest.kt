package so.kontext.video.internal.measurement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PolygonTest {
    private val square = Polygon.corners(10f, 10f)

    @Test fun theAreaOfASquareAndATriangleWhateverTheWinding() {
        assertEquals(100.0, Polygon.area(square), 1e-9)
        assertEquals(6.0, Polygon.area(floatArrayOf(0f, 0f, 4f, 0f, 0f, 3f)), 1e-9)
        assertEquals(6.0, Polygon.area(floatArrayOf(0f, 0f, 0f, 3f, 4f, 0f)), 1e-9, "the other winding")
        assertEquals(-Polygon.signedArea(floatArrayOf(0f, 0f, 4f, 0f, 0f, 3f)), Polygon.signedArea(floatArrayOf(0f, 0f, 0f, 3f, 4f, 0f)), 1e-9)
    }

    @Test fun aPolygonFullyInsideIsKeptWhole() {
        val inside = floatArrayOf(2f, 2f, 8f, 2f, 8f, 8f, 2f, 8f)
        assertEquals(36.0, Polygon.area(Polygon.clip(inside, square)), 1e-6)
    }

    @Test fun aPolygonFullyOutsideIsCutAway() {
        val outside = floatArrayOf(20f, 20f, 30f, 20f, 30f, 30f, 20f, 30f)
        assertEquals(0.0, Polygon.area(Polygon.clip(outside, square)), 1e-9)
    }

    @Test fun aPartlyOutsidePolygonKeepsWhatOverlaps() {
        val shifted = floatArrayOf(5f, 0f, 15f, 0f, 15f, 10f, 5f, 10f)
        assertEquals(50.0, Polygon.area(Polygon.clip(shifted, square)), 1e-6)
    }

    @Test fun aRotatedClipperCutsOnItsEdgesNotItsBoundingBox() {
        // The square turned 45° about its centre: a diamond of area 100 whose bounding box covers the whole square and more.
        val half = (5 * Math.sqrt(2.0)).toFloat()
        val diamond = floatArrayOf(5f, 5f - half, 5f + half, 5f, 5f, 5f + half, 5f - half, 5f)
        val area = Polygon.area(Polygon.clip(square, diamond))
        assertTrue(area < 100.0, "the square's corners are outside the diamond: $area")
        assertEquals(100.0 * (2 * Math.sqrt(2.0) - 2), area, 1e-3, "the regular octagon both share")
    }

    @Test fun emptyInputGivesNothing() {
        assertEquals(0, Polygon.clip(FloatArray(0), square).size)
        assertEquals(0, Polygon.clip(square, FloatArray(0)).size, "a clipper of fewer than three points clips everything")
        assertEquals(0.0, Polygon.area(FloatArray(0)), 1e-9)
    }

    @Test fun aClockwiseClipperCutsAsACounterClockwiseOne() {
        val clockwise = floatArrayOf(0f, 0f, 0f, 10f, 10f, 10f, 10f, 0f)
        assertTrue(Polygon.signedArea(clockwise) < 0 && Polygon.signedArea(square) > 0)
        val shifted = floatArrayOf(5f, 0f, 15f, 0f, 15f, 10f, 5f, 10f)
        assertEquals(50.0, Polygon.area(Polygon.clip(shifted, clockwise)), 1e-6)
        assertEquals(100.0, Polygon.area(Polygon.clip(square, clockwise)), 1e-6)
    }

    @Test fun shapesThatOnlyTouchShareNoArea() {
        val beside = floatArrayOf(10f, 0f, 20f, 0f, 20f, 10f, 10f, 10f)
        assertEquals(0.0, Polygon.area(Polygon.clip(beside, square)), 1e-6, "an edge in common")
        val corner = floatArrayOf(10f, 10f, 20f, 10f, 20f, 20f, 10f, 20f)
        assertEquals(0.0, Polygon.area(Polygon.clip(corner, square)), 1e-6, "a corner in common")
    }

    @Test fun aShapeClippedByItselfIsWholeAndAFlatOneHasNoArea() {
        assertEquals(100.0, Polygon.area(Polygon.clip(square, square)), 1e-6)
        assertEquals(0.0, Polygon.area(floatArrayOf(0f, 0f, 5f, 5f, 10f, 10f)), 1e-9, "three points on one line")
        assertEquals(0.0, Polygon.area(floatArrayOf(0f, 0f, 5f, 5f)), 1e-9, "two points")
        assertEquals(0.0, Polygon.area(Polygon.corners(0f, 10f)), 1e-9, "a view without a width")
        assertEquals(0.0, Polygon.area(Polygon.clip(square, Polygon.corners(0f, 10f))), 1e-9, "a clipper without a width")
    }

    @Test fun aSubjectLargerThanTheClipperIsCutToIt() {
        val large = floatArrayOf(-100f, -100f, 100f, -100f, 100f, 100f, -100f, 100f)
        assertEquals(100.0, Polygon.area(Polygon.clip(large, square)), 1e-6)
    }
}
