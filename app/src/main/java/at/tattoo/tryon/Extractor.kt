package at.tattoo.tryon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Freistell-Logik. Endgültige Deckkraft eines Pixels:
 *   max(hautfilter * keep, force)
 * keep  = Lasso + Radierer (255 = behalten)
 * force = "Zurückholen"-Pinsel (erzwingt Sichtbarkeit)
 * hautfilter = aus Hautfarb-Proben berechnet (Modus Tinte oder Farbabstand)
 */
class Extractor(src: Bitmap) {

    enum class Mode { INK, COLOR }

    class Params(val samples: IntArray, val mode: Mode, val tolerance: Float, val softness: Float)
    class KeyResult(val alpha: ByteArray, val nearest: ByteArray, val samples: IntArray)
    private class Snapshot(val keep: ByteArray, val force: ByteArray, val samples: List<Int>)

    val w: Int = src.width
    val h: Int = src.height
    val original: Bitmap = src

    private val px = IntArray(w * h)
    private val keep = ByteArray(w * h)
    private val force = ByteArray(w * h)
    private var key = ByteArray(w * h)
    private var nearest = ByteArray(w * h)
    private var keySamples = IntArray(0)
    private val out = IntArray(w * h)
    private val undoStack = ArrayDeque<Snapshot>()

    val result: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    val samples = ArrayList<Int>()
    var mode = Mode.INK
    var tolerance = 0.15f
    var softness = 0.15f

    init {
        src.getPixels(px, 0, w, 0, 0, w, h)
        keep.fill(FULL)
        key.fill(FULL)
        composeAll()
    }

    fun params(): Params = Params(samples.toIntArray(), mode, tolerance, softness)

    /** Berechnet den Hautfilter. Liest nur unveränderliche Pixeldaten -> darf im Hintergrund laufen. */
    fun computeKey(p: Params): KeyResult {
        val n = px.size
        val alpha = ByteArray(n)
        val near = ByteArray(n)
        val s = p.samples
        if (s.isEmpty()) {
            alpha.fill(FULL)
            return KeyResult(alpha, near, s)
        }
        val k = s.size
        val sr = FloatArray(k) { ((s[it] shr 16) and 0xFF).toFloat() }
        val sg = FloatArray(k) { ((s[it] shr 8) and 0xFF).toFloat() }
        val sb = FloatArray(k) { (s[it] and 0xFF).toFloat() }
        val isr = FloatArray(k) { 1f / max(sr[it], 1f) }
        val isg = FloatArray(k) { 1f / max(sg[it], 1f) }
        val isb = FloatArray(k) { 1f / max(sb[it], 1f) }
        val e0 = p.tolerance
        val e1 = p.tolerance + max(p.softness, 0.002f)
        val inv = 1f / (e1 - e0)
        val colorMode = p.mode == Mode.COLOR

        for (i in 0 until n) {
            val c = px[i]
            val r = ((c shr 16) and 0xFF).toFloat()
            val g = ((c shr 8) and 0xFF).toFloat()
            val b = (c and 0xFF).toFloat()
            var best = Float.MAX_VALUE
            var bestIdx = 0
            if (colorMode) {
                for (j in 0 until k) {
                    val dr = r - sr[j]
                    val dg = g - sg[j]
                    val db = b - sb[j]
                    val d = dr * dr + dg * dg + db * db
                    if (d < best) {
                        best = d
                        bestIdx = j
                    }
                }
                best = sqrt(best) / 441.673f
            } else {
                for (j in 0 until k) {
                    val ratio = min(min(r * isr[j], g * isg[j]), b * isb[j])
                    val ink = 1f - min(ratio, 1f)
                    if (ink < best) {
                        best = ink
                        bestIdx = j
                    }
                }
            }
            var t = (best - e0) * inv
            t = if (t <= 0f) 0f else if (t >= 1f) 1f else t * t * (3f - 2f * t)
            alpha[i] = (t * 255f + 0.5f).toInt().toByte()
            near[i] = bestIdx.toByte()
        }
        return KeyResult(alpha, near, s)
    }

    fun applyKey(r: KeyResult) {
        key = r.alpha
        nearest = r.nearest
        keySamples = r.samples
    }

    fun composeAll() = compose(0, 0, w, h)

    /** Setzt das Ergebnisbild im Bereich [x0,x1) x [y0,y1) neu zusammen. */
    fun compose(x0: Int, y0: Int, x1: Int, y1: Int) {
        val ks = keySamples
        for (y in y0 until y1) {
            var i = y * w + x0
            for (x in x0 until x1) {
                val p = px[i]
                val kA = key[i].toInt() and 0xFF
                val kp = keep[i].toInt() and 0xFF
                val f = force[i].toInt() and 0xFF
                val a1 = kA * kp / 255
                var a: Int
                val rgb: Int
                if (f >= a1) {
                    a = f
                    rgb = p and 0xFFFFFF
                } else {
                    a = a1
                    val idx = nearest[i].toInt() and 0xFF
                    rgb = if (kA in 13..249 && idx < ks.size) decontaminate(p, ks[idx], kA) else p and 0xFFFFFF
                }
                a = a * (p ushr 24) / 255
                out[i] = (a shl 24) or rgb
                i++
            }
        }
        result.setPixels(out, y0 * w + x0, w, x0, y0, x1 - x0, y1 - y0)
    }

    /** Entfernt den Hautanteil aus halbtransparenten Kantenpixeln: C = (P - (1-a)*S) / a */
    private fun decontaminate(p: Int, s: Int, a: Int): Int {
        val r = channel((p shr 16) and 0xFF, (s shr 16) and 0xFF, a)
        val g = channel((p shr 8) and 0xFF, (s shr 8) and 0xFF, a)
        val b = channel(p and 0xFF, s and 0xFF, a)
        return (r shl 16) or (g shl 8) or b
    }

    private fun channel(pc: Int, sc: Int, a: Int): Int =
        ((pc * 255 - (255 - a) * sc) / a).coerceIn(0, 255)

    // ---------- Werkzeuge ----------

    fun applyLasso(path: Path) {
        pushUndo()
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(mask)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        c.drawPath(path, paint)
        val m = IntArray(w * h)
        mask.getPixels(m, 0, w, 0, 0, w, h)
        mask.recycle()
        for (i in 0 until w * h) {
            val a = m[i] ushr 24
            if (a < 255) {
                keep[i] = ((keep[i].toInt() and 0xFF) * a / 255).toByte()
                force[i] = ((force[i].toInt() and 0xFF) * a / 255).toByte()
            }
        }
        composeAll()
    }

    /** Median-Farbe entlang der Lasso-Linie = vermutete Hautfarbe. */
    fun sampleAlongPath(path: Path): Int? {
        val pm = PathMeasure(path, true)
        val len = pm.length
        if (len < 10f) return null
        val n = 240
        val pos = FloatArray(2)
        val rs = ArrayList<Int>(n)
        val gs = ArrayList<Int>(n)
        val bs = ArrayList<Int>(n)
        for (k in 0 until n) {
            if (!pm.getPosTan(len * k / n, pos, null)) continue
            val x = pos[0].toInt()
            val y = pos[1].toInt()
            if (x < 0 || y < 0 || x >= w || y >= h) continue
            val c = px[y * w + x]
            rs.add((c shr 16) and 0xFF)
            gs.add((c shr 8) and 0xFF)
            bs.add(c and 0xFF)
        }
        if (rs.size < 10) return null
        rs.sort(); gs.sort(); bs.sort()
        val mid = rs.size / 2
        return Color.rgb(rs[mid], gs[mid], bs[mid])
    }

    /** Durchschnittsfarbe um einen Punkt (für das Antippen der Haut). */
    fun averageAt(cx: Int, cy: Int, radius: Int = 3): Int? {
        if (cx < 0 || cy < 0 || cx >= w || cy >= h) return null
        var r = 0L
        var g = 0L
        var b = 0L
        var cnt = 0
        for (y in max(0, cy - radius)..min(h - 1, cy + radius)) {
            for (x in max(0, cx - radius)..min(w - 1, cx + radius)) {
                val c = px[y * w + x]
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
                cnt++
            }
        }
        if (cnt == 0) return null
        return Color.rgb((r / cnt).toInt(), (g / cnt).toInt(), (b / cnt).toInt())
    }

    fun addSample(color: Int) {
        pushUndo()
        samples.add(color)
        while (samples.size > 8) samples.removeAt(0)
    }

    fun clearSamples() {
        pushUndo()
        samples.clear()
    }

    fun beginStroke() = pushUndo()

    /** Weicher runder Pinsel in Bildkoordinaten. Gibt den geänderten Bereich zurück. */
    fun brush(cx: Float, cy: Float, radius: Float, erase: Boolean): Rect? {
        val r = max(radius, 1f)
        val x0 = max(0, floor(cx - r).toInt())
        val x1 = min(w, ceil(cx + r).toInt() + 1)
        val y0 = max(0, floor(cy - r).toInt())
        val y1 = min(h, ceil(cy + r).toInt() + 1)
        if (x0 >= x1 || y0 >= y1) return null
        val inner = r * 0.55f
        val r2 = r * r
        for (y in y0 until y1) {
            val dy = y + 0.5f - cy
            for (x in x0 until x1) {
                val dx = x + 0.5f - cx
                val d2 = dx * dx + dy * dy
                if (d2 > r2) continue
                val d = sqrt(d2)
                val f = if (d <= inner) 1f else 1f - (d - inner) / (r - inner)
                val v = (f * 255f).toInt().coerceIn(0, 255)
                val i = y * w + x
                if (erase) {
                    val lim = 255 - v
                    if ((keep[i].toInt() and 0xFF) > lim) keep[i] = lim.toByte()
                    if ((force[i].toInt() and 0xFF) > lim) force[i] = lim.toByte()
                } else {
                    if ((force[i].toInt() and 0xFF) < v) force[i] = v.toByte()
                    if ((keep[i].toInt() and 0xFF) < v) keep[i] = v.toByte()
                }
            }
        }
        compose(x0, y0, x1, y1)
        return Rect(x0, y0, x1, y1)
    }

    fun resetAll() {
        pushUndo()
        keep.fill(FULL)
        force.fill(0)
        samples.clear()
        composeAll()
    }

    private fun pushUndo() {
        undoStack.addLast(Snapshot(keep.copyOf(), force.copyOf(), samples.toList()))
        while (undoStack.size > 10) undoStack.removeFirst()
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    fun undo(): Boolean {
        val s = undoStack.removeLastOrNull() ?: return false
        s.keep.copyInto(keep)
        s.force.copyInto(force)
        samples.clear()
        samples.addAll(s.samples)
        composeAll()
        return true
    }

    companion object {
        private const val FULL: Byte = -1 // 0xFF
    }
}
