package so.kontext.video.internal.measurement

import kotlin.math.abs

/**
 * Plane polygons as flat arrays, x0, y0, x1, y1, …: the arithmetic behind [ViewExposure]. Pure, no Android
 * types, so it runs in a plain unit test.
 */
internal object Polygon {
    /** A width×height rectangle at the origin as a polygon: x0, y0, x1, y1, … */
    fun corners(width: Float, height: Float) = floatArrayOf(0f, 0f, width, 0f, width, height, 0f, height)

    /** The area of a polygon (shoelace formula), whatever its winding. */
    fun area(polygon: FloatArray): Double = abs(signedArea(polygon))

    fun signedArea(polygon: FloatArray): Double {
        val count = polygon.size / 2
        if (count < 3) return 0.0
        var twice = 0.0
        for (index in 0 until count) {
            val next = (index + 1) % count
            twice += polygon[index * 2].toDouble() * polygon[next * 2 + 1] - polygon[next * 2].toDouble() * polygon[index * 2 + 1]
        }
        return twice / 2
    }

    /**
     * [subject] cut by the convex polygon [clipper] (Sutherland–Hodgman). Every outline here is the image of a
     * rectangle under an affine transform, so the clipper is always convex.
     */
    fun clip(subject: FloatArray, clipper: FloatArray): FloatArray {
        val clipCount = clipper.size / 2
        if (clipCount < 3) return FloatArray(0)
        // The clipper's winding decides which side of each edge is inside.
        val sign = if (signedArea(clipper) >= 0) 1.0 else -1.0
        var output = subject
        for (edge in 0 until clipCount) {
            if (output.isEmpty()) break
            val next = (edge + 1) % clipCount
            output = clipByEdge(output, clipper[edge * 2], clipper[edge * 2 + 1], clipper[next * 2], clipper[next * 2 + 1], sign)
        }
        return output
    }

    /** [input] less what lies outside the edge a→b. */
    private fun clipByEdge(input: FloatArray, ax: Float, ay: Float, bx: Float, by: Float, sign: Double): FloatArray {
        val inside = { x: Double, y: Double -> sign * ((bx - ax) * (y - ay) - (by - ay) * (x - ax)) >= 0 }
        val output = mutableListOf<Float>()
        val count = input.size / 2
        for (index in 0 until count) {
            val cx = input[index * 2].toDouble()
            val cy = input[index * 2 + 1].toDouble()
            val previous = (index + count - 1) % count
            val px = input[previous * 2].toDouble()
            val py = input[previous * 2 + 1].toDouble()
            val currentIn = inside(cx, cy)
            if (currentIn != inside(px, py)) {
                // Where the segment previous→current crosses the edge's line.
                val dx = cx - px
                val dy = cy - py
                val denominator = dx * (by - ay) - dy * (bx - ax)
                val t = if (abs(denominator) < 1e-12) 1.0 else ((ax - px) * (by - ay) - (ay - py) * (bx - ax)) / denominator
                output += (px + t * dx).toFloat()
                output += (py + t * dy).toFloat()
            }
            if (currentIn) {
                output += cx.toFloat()
                output += cy.toFloat()
            }
        }
        return output.toFloatArray()
    }
}
