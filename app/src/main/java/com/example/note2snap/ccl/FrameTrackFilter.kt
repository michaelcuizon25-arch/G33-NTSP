package com.example.note2snap.ccl

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Conservative source-image verification of fragmented, inset metal-frame tracks.
 * No pixel/coordinate changes and no OCR character checks. A long thin seed plus
 * multiple aligned fragments must be supported by long image edges and a groove around a
 * neutral strip. Local darker marks are retained even when they overlap the strip.
 */
internal class FrameTrackFilter private constructor(
    private val pixels: IntArray, private val luma: ByteArray, private val foreground: BooleanArray, private val width: Int, private val height: Int,
    private val median: Int, internal val tracks: List<Track>
) {
    internal data class Track(val intercept: Double, val slope: Double, val peer: Int,
                              val top: Int, val bottom: Int, val support: Double, val third: Int = 0) {
        fun x(y: Int) = (intercept + slope * y).roundToInt()
    }

    private val supportRows by lazy { tracks.associateWith { track -> BooleanArray(height) { supported(track,it) } } }

    private val edgePositions by lazy { tracks.associateWith { track -> IntArray(height) { edgePosition(track.x(it),it) } } }

    fun isFrameComponent(box: Rect, researchEvidence: ((String) -> Unit)? = null): Boolean {
        if (box.isEmpty || box.left < 0 || box.top < 0 || box.right > width || box.bottom > height ||
            box.width() > max(4, median / 2)) return false
        // Short components wider than a thin edge can be serifed/branched glyphs.
        if (box.height() < median * 3 && box.width() > max(3,median / 6)) return false
        for (track in tracks) {
            val y = box.centerY()
            val edge = track.x(y)
            if (box.top < track.top || box.bottom > track.bottom ||
                box.left < min(edge, edge + track.peer) - 6 ||
                box.right > max(edge, edge + track.peer) + 6) continue
            // Only the supported outer edge is a frame candidate; never remove
            // writing elsewhere on the neutral strip merely because it is nearby.
            if (box.left < edge - 10 || box.right > edge + 10) continue
            val rows = supportRows.getValue(track)
            val positions = edgePositions.getValue(track)
            // Compare the original RGB profile to neighbouring supported frame rows.
            // Darker ink, colour changes or insufficient background evidence abstain.
            var darkExcess = 0.0
            var absoluteError = 0.0
            var count = 0
            var stronglyDifferent = 0
            var unavailable = false
            for (py in box.top until box.bottom) for (px in box.left until box.right) {
                if (!foreground[py * width + px]) continue
                val reference = background(track, rows, positions, px, py)
                if (reference == null) { unavailable = true; break }
                val actual = pixels[py * width + px]
                val expectedGray = reference
                val delta = expectedGray - (luma[py * width + px].toInt() and 255)
                darkExcess += max(0.0, delta)
                absoluteError += abs(delta)
                val chroma = max(Color.red(actual),max(Color.green(actual),Color.blue(actual))) -
                    min(Color.red(actual),min(Color.green(actual),Color.blue(actual)))
                if (abs(delta) > 16 || chroma > 24) stronglyDifferent++
                count++
            }
            if(researchEvidence!=null) try {
                researchEvidence("box=$box count=$count dark=${darkExcess/max(1,count)} absolute=${absoluteError/max(1,count)} strong=$stronglyDifferent unavailable=$unavailable")
            } catch (_: Exception) { }
            if (!unavailable && count > 0 && darkExcess / count <= 7.0 && absoluteError / count <= 8.0 &&
                stronglyDifferent <= max(1, count / 16)) return true
        }
        return false
    }

    private fun background(track: Track, rows: BooleanArray, positions: IntArray, x: Int, y: Int): Double? {
        val pairs=mutableListOf<Double>()
        for(offset in 1..3) {
            val above=y-offset*median;val below=y+offset*median
            if(above !in track.top until track.bottom || below !in track.top until track.bottom || !rows[above] || !rows[below]) continue
            val ax=x+positions[above]-positions[y];val bx=x+positions[below]-positions[y]
            if(ax !in 0 until width || bx !in 0 until width) continue
            pairs.add(((luma[above*width+ax].toInt() and 255)+(luma[below*width+bx].toInt() and 255))/2.0)
        }
        // Median symmetric interpolation tolerates a neighbouring ink mark without
        // allowing that mark to darken the expected background of a real glyph.
        if(pairs.size>=2) {pairs.sort();return pairs[pairs.size/2]}
        var n = 0
        var sumY = 0.0; var sumG = 0.0; var sumYY = 0.0; var sumYG = 0.0
        for (offset in intArrayOf(-3,-2,-1,1,2,3)) {
            val ry = y + offset * median
            if (ry !in track.top until track.bottom || !rows[ry]) continue
            val rx = x + positions[ry] - positions[y]
            if (rx !in 0 until width) continue
            val g = (luma[ry * width + rx].toInt() and 255).toDouble()
            val dy = (ry - y).toDouble()
            n++; sumY += dy; sumG += g; sumYY += dy * dy; sumYG += dy * g
        }
        if (n < 3) return null
        val denominator = n * sumYY - sumY * sumY
        if (denominator <= 0) return null
        val slope = (n * sumYG - sumY * sumG) / denominator
        return (sumG - slope * sumY) / n
    }

    private fun supported(track: Track, y: Int): Boolean =
        edge(track.x(y), y) >= 10 && edge(track.x(y) + track.peer, y) >= 10 &&
            neutral(track.x(y) + track.peer / 2, y) &&
            (track.third == 0 || edge(track.x(y) + track.third,y) >= 10)

    private fun edge(x: Int, y: Int): Int {
        var best = 0
        for (dx in -6..6) {
            val p = x + dx
            if (p >= 3 && p + 3 < width)
                best = max(best, abs((luma[y * width + p - 3].toInt() and 255) - (luma[y * width + p + 3].toInt() and 255)))
        }
        return best
    }

    private fun edgePosition(x: Int, y: Int): Int {
        val best=edge(x,y)
        var weighted=0; var weight=0
        for(dx in -6..6) {
            val p=x+dx
            if(p<3 || p+3>=width) continue
            val contrast=abs((luma[y*width+p-3].toInt() and 255)-(luma[y*width+p+3].toInt() and 255))
            if(contrast*5>=best*4) {weighted+=p*contrast;weight+=contrast}
        }
        return if(weight==0) x else (weighted.toDouble()/weight).roundToInt()
    }

    private fun neutral(x: Int, y: Int): Boolean {
        if (x !in 0 until width) return false
        val p = pixels[y * width + x]
        val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
        return max(r,max(g,b)) - min(r,min(g,b)) <= 20 && (luma[y * width + x].toInt() and 255) >= 110
    }

    companion object {
        fun detect(source: Bitmap, boxes: List<Rect>, medianTextHeight: Int, foreground: BooleanArray): FrameTrackFilter? {
            val w = source.width; val h = source.height
            if (foreground.size != w*h) return null
            val median = medianTextHeight.coerceAtLeast(8)
            val thin = boxes.filter { it.width() in 1..max(4, median / 2) && it.height() >= 6 }
            val longSeeds = thin.filter { it.height() >= max(h / 10, median * 3) && it.height() >= it.width() * 12 }
            val bin=max(8,median/2)
            val brokenSeeds = listOf(0,bin/2).flatMap { phase ->
                thin.groupBy { (it.centerX()+phase)/bin }.values
                    .filter { it.size >= 8 && it.maxOf { b -> b.bottom } - it.minOf { b -> b.top } >= h * 0.55 }
                    .map { group -> group.maxBy { it.height() } }
            }
            val seeds = (longSeeds + brokenSeeds).distinct().sortedByDescending { it.height() }.take(8)
            if (seeds.isEmpty()) return null
            var pixels: IntArray? = null
            var luma: ByteArray? = null
            val computedColumns=BooleanArray(w)
            val tracks = mutableListOf<Track>()
            for (seed in seeds) {
                if (tracks.size >= 2) break // Bounded work; uncertain extra hypotheses are retained.
                if (tracks.any { abs(it.x(seed.centerY()) - seed.centerX()) <= median / 2 }) continue
                var intercept = seed.centerX().toDouble(); var slope = 0.0
                var aligned = emptyList<Rect>()
                repeat(3) {
                    aligned = thin.filter { abs(it.exactCenterX() - (intercept + slope * it.centerY())) <= max(4, median / 4) }
                    if (aligned.size >= 6) {
                        val mx = aligned.map { it.exactCenterX().toDouble() }.average()
                        val my = aligned.map { it.exactCenterY().toDouble() }.average()
                        val variance = aligned.sumOf { (it.exactCenterY() - my) * (it.exactCenterY() - my) }
                        if (variance > 0) {
                            slope = aligned.sumOf { (it.exactCenterY() - my) * (it.exactCenterX() - mx) } / variance
                            intercept = mx - slope * my
                        }
                    }
                }
                if (aligned.size < 6 || abs(slope) > 0.08 ||
                    aligned.maxOf { it.bottom } - aligned.minOf { it.top } < h * 0.4 ||
                    aligned.map { min(3, it.centerY() * 4 / h) }.distinct().size < 3) continue
                val data = pixels ?: IntArray(w*h).also { source.getPixels(it,0,w,0,0,w,h); pixels=it }
                val luminance = luma ?: ByteArray(data.size).also { luma=it }
                // Only candidate corridors are queried. Avoid converting the whole photo.
                val radius=min(w/8,median*4)+12
                val x0=intercept.roundToInt()
                val x1=(intercept+slope*(h-1)).roundToInt()
                val left=(min(x0,x1)-radius).coerceAtLeast(0)
                val right=(max(x0,x1)+radius).coerceAtMost(w-1)
                for(y in 0 until h) for(x in left..right) if(!computedColumns[x])
                    luminance[y*w+x]=gray(data[y*w+x]).toByte()
                for(x in left..right) computedColumns[x]=true
                val probe = FrameTrackFilter(data,luminance,foreground,w,h,median,emptyList())
                val step = max(1, h / 128)
                val total = (0 until h step step).count()
                var best: Track? = null
                for (direction in listOf(-1,1)) for (distance in max(16,median / 2)..min(w / 10,median * 3) step 4) {
                    val peer = direction * distance
                    val candidate = Track(intercept,slope,peer,0,h,0.0)
                    var support = 0; var first = h; var last = 0
                    for (y in 0 until h step step) if (probe.supported(candidate,y)) {
                        support++; first=min(first,y); last=max(last,y)
                    }
                    if (support < total * 0.5 || last - first < h * 0.55) continue
                    val track = Track(intercept,slope,peer,first,min(h,last+step),support.toDouble()/total)
                    if (best == null || track.support > best.support) best=track
                }
                // A simple filled gray stroke has only two edges. Require a separate
                // groove/lip edge as well, so even a very tall light-gray I abstains.
                best?.let { pair ->
                    var verified: Track? = null
                    val direction = if (pair.peer < 0) -1 else 1
                    for (distance in 20..min(w / 8,median * 4) step 4) {
                        if (abs(distance - abs(pair.peer)) < 20) continue
                        val candidate = pair.copy(third = direction * distance)
                        var support = 0; var first = h; var last = 0
                        for (y in 0 until h step step) if (probe.supported(candidate,y)) {
                            support++; first=min(first,y); last=max(last,y)
                        }
                        if (support < total * 0.5 || last-first < h*0.55) continue
                        if (verified == null || support.toDouble()/total > verified.support)
                            verified = candidate.copy(top=first,bottom=min(h,last+step),support=support.toDouble()/total)
                    }
                    verified?.let { tracks.add(it) }
                }
            }
            return if (tracks.isEmpty()) null else FrameTrackFilter(pixels!!,luma!!,foreground,w,h,median,tracks)
        }

        private fun gray(pixel: Int): Int =
            (Color.red(pixel)*299 + Color.green(pixel)*587 + Color.blue(pixel)*114)/1000
    }
}
