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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Freistell-Logik. Endgültige Deckkraft eines Pixels:
 *   max(hautfilter * keep, force)
 * keep  = erkannte Region / Lasso + Radierer (255 = behalten)
 * force = "Zurückholen"-Pinsel (erzwingt Sichtbarkeit)
 * hautfilter:
 *   AUTO  = Verhältnis zur lokal geschätzten Hautfarbe (automatische Erkennung)
 *   INK   = Verhältnis zu angetippten Hautproben
 *   COLOR = Farbabstand zu angetippten Hautproben
 */
class Extractor(src: Bitmap) {

    enum class Mode { AUTO, INK, COLOR }

    class Params(
        val samples: IntArray,
        val mode: Mode,
        val tolerance: Float,
        val softness: Float,
        val localSkin: IntArray?
    )

    class KeyResult(val alpha: ByteArray, val nearest: ByteArray, val samples: IntArray, val localSkin: IntArray?)

    /**
     * KI-Hinweise für die Erkennung (Abfragen in Bildkoordinaten):
     * body  = Körperhaut laut KI-Segmentierung (Hintergrund/Kleidung ausschließen)
     * limit = angetipptes Objekt laut KI-Auswahl
     * focus = angetippte Stelle (bevorzugter Tattoo-Bereich)
     */
    class AiHints(
        val body: ((Float, Float) -> Boolean)? = null,
        val limit: ((Float, Float) -> Boolean)? = null,
        val focusX: Float = -1f,
        val focusY: Float = -1f
    )

    /** Ergebnis der automatischen Erkennung (volle Auflösung). */
    class AutoResult(val localSkin: IntArray, val region: ByteArray, val found: Boolean)

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
    private var keySkin: IntArray? = null
    private var localSkin: IntArray? = null
    private val out = IntArray(w * h)
    private val undoStack = ArrayDeque<Snapshot>()

    val result: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    val samples = ArrayList<Int>()
    var mode = Mode.AUTO
    var tolerance = 0.15f
    var softness = 0.15f

    /** true, wenn das Bild bereits freigestellt ist (PNG mit Transparenz). */
    val hasTransparency: Boolean

    init {
        src.getPixels(px, 0, w, 0, 0, w, h)
        var transparent = 0
        for (c in px) if ((c ushr 24) < 250) transparent++
        hasTransparency = transparent > px.size / 50
        keep.fill(FULL)
        key.fill(FULL)
        composeAll()
    }

    fun params(): Params = Params(samples.toIntArray(), mode, tolerance, softness, localSkin)

    /** Berechnet den Hautfilter. Liest nur unveränderliche Daten -> darf im Hintergrund laufen. */
    fun computeKey(p: Params): KeyResult {
        val n = px.size
        val alpha = ByteArray(n)
        val near = ByteArray(n)
        val s = p.samples
        val skinMap = p.localSkin
        val autoMode = p.mode == Mode.AUTO
        if (hasTransparency || (autoMode && skinMap == null) || (!autoMode && s.isEmpty())) {
            alpha.fill(FULL)
            return KeyResult(alpha, near, s, null)
        }
        val e0 = p.tolerance
        val e1 = p.tolerance + max(p.softness, 0.002f)
        val inv = 1f / (e1 - e0)

        if (autoMode && skinMap != null) {
            for (i in 0 until n) {
                val c = px[i]
                val sk = skinMap[i]
                val rr = ((c shr 16) and 0xFF) / max(((sk shr 16) and 0xFF).toFloat(), 1f)
                val gg = ((c shr 8) and 0xFF) / max(((sk shr 8) and 0xFF).toFloat(), 1f)
                val bb = (c and 0xFF) / max((sk and 0xFF).toFloat(), 1f)
                val ink = 1f - min(min(min(rr, gg), bb), 1f)
                alpha[i] = smooth(ink, e0, inv)
            }
            return KeyResult(alpha, near, s, skinMap)
        }

        val k = s.size
        val sr = FloatArray(k) { ((s[it] shr 16) and 0xFF).toFloat() }
        val sg = FloatArray(k) { ((s[it] shr 8) and 0xFF).toFloat() }
        val sb = FloatArray(k) { (s[it] and 0xFF).toFloat() }
        val isr = FloatArray(k) { 1f / max(sr[it], 1f) }
        val isg = FloatArray(k) { 1f / max(sg[it], 1f) }
        val isb = FloatArray(k) { 1f / max(sb[it], 1f) }
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
            alpha[i] = smooth(best, e0, inv)
            near[i] = bestIdx.toByte()
        }
        return KeyResult(alpha, near, s, null)
    }

    private fun smooth(x: Float, e0: Float, inv: Float): Byte {
        var t = (x - e0) * inv
        t = if (t <= 0f) 0f else if (t >= 1f) 1f else t * t * (3f - 2f * t)
        return (t * 255f + 0.5f).toInt().toByte()
    }

    fun applyKey(r: KeyResult) {
        key = r.alpha
        nearest = r.nearest
        keySamples = r.samples
        keySkin = r.localSkin
    }

    fun composeAll() = compose(0, 0, w, h)

    /** Setzt das Ergebnisbild im Bereich [x0,x1) x [y0,y1) neu zusammen. */
    fun compose(x0: Int, y0: Int, x1: Int, y1: Int) {
        val ks = keySamples
        val skinMap = keySkin
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
                    rgb = if (kA in 13..249) {
                        if (skinMap != null) {
                            decontaminate(p, skinMap[i], kA)
                        } else {
                            val idx = nearest[i].toInt() and 0xFF
                            if (idx < ks.size) decontaminate(p, ks[idx], kA) else p and 0xFFFFFF
                        }
                    } else {
                        p and 0xFFFFFF
                    }
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

    // ---------- Automatische Erkennung ----------

    /**
     * Findet das Tattoo automatisch. Läuft im Hintergrund (liest nur Pixel).
     * 1. Lokale Hautfarbe per morphologischem Closing (entfernt dunkle, schmale Linien) + Weichzeichnen
     * 2. "Tinte" = deutlich dunkler/farbiger als die lokale Haut
     * 3. Tinten-Pixel zu Clustern verbinden, den größten (mittigen) Cluster wählen, Löcher füllen
     */
    fun autoDetect(hints: AiHints? = null): AutoResult {
        val scale = min(1f, 420f / max(w, h))
        val aw = max(8, (w * scale).roundToInt())
        val ah = max(8, (h * scale).roundToInt())
        val small = Bitmap.createScaledBitmap(original, aw, ah, true)
        val sp = IntArray(aw * ah)
        small.getPixels(sp, 0, aw, 0, 0, aw, ah)
        if (small !== original) small.recycle()
        val n = aw * ah

        if (hasTransparency) {
            val region = ByteArray(w * h)
            for (i in 0 until w * h) region[i] = if ((px[i] ushr 24) > 8) FULL else 0
            return AutoResult(IntArray(w * h) { Color.WHITE }, region, true)
        }

        val cr = IntArray(n) { (sp[it] shr 16) and 0xFF }
        val cg = IntArray(n) { (sp[it] shr 8) and 0xFF }
        val cb = IntArray(n) { sp[it] and 0xFF }

        // ---------- KI-Bereiche auf Analyse-Auflösung ----------
        val fx = w.toFloat() / aw
        val fy = h.toFloat() / ah
        val grow = max(2, (max(aw, ah) * 0.03f).toInt())
        var bodyA: IntArray? = hints?.body?.let { f ->
            IntArray(n) { if (f((it % aw + 0.5f) * fx, (it / aw + 0.5f) * fy)) 1 else 0 }
        }
        if (bodyA != null) {
            if (bodyA.count { it == 1 } < n * 0.03f) {
                bodyA = null // keine Person im Bild (z. B. Motiv auf Papier)
            } else {
                // Tattoo-Flächen liegen als "Löcher" in der Haut -> füllen, dann etwas vergrößern
                fillHoles(bodyA, aw, ah)
                bodyA = filter(bodyA, aw, ah, grow, true)
            }
        }
        var limitA: IntArray? = hints?.limit?.let { f ->
            IntArray(n) { if (f((it % aw + 0.5f) * fx, (it / aw + 0.5f) * fy)) 1 else 0 }
        }
        if (limitA != null) {
            if (limitA.count { it == 1 } < 20) {
                limitA = null
            } else {
                fillHoles(limitA, aw, ah)
                limitA = filter(limitA, aw, ah, grow, true)
            }
        }
        val bodyF: IntArray? = bodyA
        val limitF: IntArray? = limitA
        val allowed = BooleanArray(n)
        for (i in 0 until n) {
            allowed[i] = (bodyF == null || bodyF[i] == 1) && (limitF == null || limitF[i] == 1)
        }
        val focusIdx = if (hints != null && hints.focusX >= 0f && hints.focusY >= 0f) {
            val ax = (hints.focusX / fx).toInt().coerceIn(0, aw - 1)
            val ay = (hints.focusY / fy).toInt().coerceIn(0, ah - 1)
            ay * aw + ax
        } else {
            -1
        }

        val r = max(5, (max(aw, ah) * 0.06f).toInt())
        val skR = closing(cr, aw, ah, r)
        val skG = closing(cg, aw, ah, r)
        val skB = closing(cb, aw, ah, r)
        val br = max(2, r / 2)
        boxBlur(skR, aw, ah, br)
        boxBlur(skG, aw, ah, br)
        boxBlur(skB, aw, ah, br)

        // Tinten-Maske
        val ink = IntArray(n)
        var inkCount = 0
        for (i in 0 until n) {
            val rr = cr[i] / max(skR[i].toFloat(), 1f)
            val gg = cg[i] / max(skG[i].toFloat(), 1f)
            val bb = cb[i] / max(skB[i].toFloat(), 1f)
            val v = 1f - min(min(min(rr, gg), bb), 1f)
            if (v > 0.28f && allowed[i]) {
                ink[i] = 1
                inkCount++
            }
        }

        // Cluster bilden
        val d = max(2, (max(aw, ah) * 0.025f).toInt())
        val dil = filter(ink, aw, ah, d, true)
        val labels = IntArray(n) { -1 }
        val queue = IntArray(n)
        var bestLabel = -1
        var bestScore = 0f
        var label = 0
        val cx0 = aw / 2f
        val cy0 = ah / 2f
        val maxDist = hypot(cx0, cy0)
        for (start in 0 until n) {
            if (dil[start] == 0 || labels[start] != -1) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = label
            var inkPixels = 0
            var sx = 0L
            var sy = 0L
            var minX = aw
            var maxX = 0
            var minY = ah
            var maxY = 0
            while (head < tail) {
                val i = queue[head++]
                val x = i % aw
                val y = i / aw
                if (ink[i] == 1) {
                    inkPixels++
                    sx += x
                    sy += y
                }
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0) { val j = i - 1; if (dil[j] == 1 && labels[j] == -1) { labels[j] = label; queue[tail++] = j } }
                if (x < aw - 1) { val j = i + 1; if (dil[j] == 1 && labels[j] == -1) { labels[j] = label; queue[tail++] = j } }
                if (y > 0) { val j = i - aw; if (dil[j] == 1 && labels[j] == -1) { labels[j] = label; queue[tail++] = j } }
                if (y < ah - 1) { val j = i + aw; if (dil[j] == 1 && labels[j] == -1) { labels[j] = label; queue[tail++] = j } }
            }
            if (inkPixels > 0) {
                val mx = sx.toFloat() / inkPixels
                val my = sy.toFloat() / inkPixels
                val dist = hypot(mx - cx0, my - cy0) / maxDist
                var score = inkPixels * (1.25f - dist)
                if (focusIdx >= 0) {
                    // Angetippte Stelle: nächster Cluster gewinnt
                    val fdx = mx - focusIdx % aw
                    val fdy = my - focusIdx / aw
                    val fd = hypot(fdx, fdy) / (max(aw, ah) * 0.1f)
                    score = inkPixels / (1f + fd * fd)
                }
                var borders = 0
                if (minX <= 1) borders++
                if (minY <= 1) borders++
                if (maxX >= aw - 2) borders++
                if (maxY >= ah - 2) borders++
                if (borders >= 2) score *= 0.5f
                if (score > bestScore) {
                    bestScore = score
                    bestLabel = label
                }
            }
            label++
        }

        // Liegt die angetippte Stelle direkt in einem Cluster, nimm genau diesen
        if (focusIdx >= 0 && labels[focusIdx] >= 0) bestLabel = labels[focusIdx]

        val found = bestLabel >= 0 && inkCount > n / 500
        val region = IntArray(n)
        if (found) {
            for (i in 0 until n) if (labels[i] == bestLabel) region[i] = 1
            fillHoles(region, aw, ah)
            val grown = filter(region, aw, ah, max(1, d / 2), true)
            System.arraycopy(grown, 0, region, 0, n)
            for (i in 0 until n) if (!allowed[i]) region[i] = 0
        } else {
            for (i in 0 until n) region[i] = if (allowed[i]) 1 else 0
        }

        // Auf volle Auflösung hochskalieren
        val skinSmall = IntArray(n) { Color.rgb(skR[it].coerceIn(0, 255), skG[it].coerceIn(0, 255), skB[it].coerceIn(0, 255)) }
        val skinBmp = Bitmap.createBitmap(skinSmall, aw, ah, Bitmap.Config.ARGB_8888)
        val skinFull = Bitmap.createScaledBitmap(skinBmp, w, h, true)
        val skinPx = IntArray(w * h)
        skinFull.getPixels(skinPx, 0, w, 0, 0, w, h)
        skinBmp.recycle()
        if (skinFull !== skinBmp) skinFull.recycle()

        val maskSmall = IntArray(n) { if (region[it] == 1) Color.WHITE else Color.TRANSPARENT }
        val maskBmp = Bitmap.createBitmap(maskSmall, aw, ah, Bitmap.Config.ARGB_8888)
        val maskFull = Bitmap.createScaledBitmap(maskBmp, w, h, true)
        val maskPx = IntArray(w * h)
        maskFull.getPixels(maskPx, 0, w, 0, 0, w, h)
        maskBmp.recycle()
        if (maskFull !== maskBmp) maskFull.recycle()
        val regionFull = ByteArray(w * h) { (maskPx[it] ushr 24).toByte() }

        return AutoResult(skinPx, regionFull, found)
    }

    /** Übernimmt das Ergebnis der Erkennung (auf dem UI-Thread aufrufen). */
    fun applyAuto(a: AutoResult) {
        pushUndo()
        localSkin = a.localSkin
        a.region.copyInto(keep)
        force.fill(0)
        mode = Mode.AUTO
    }

    private fun closing(src: IntArray, w: Int, h: Int, r: Int): IntArray =
        filter(filter(src, w, h, r, true), w, h, r, false)

    /** Separierbarer Max- (dilate) bzw. Min-Filter (erode). */
    private fun filter(src: IntArray, w: Int, h: Int, r: Int, isMax: Boolean): IntArray {
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val x1 = min(w - 1, x + r)
                var m = src[row + x0]
                for (k in x0 + 1..x1) {
                    val v = src[row + k]
                    if (isMax) {
                        if (v > m) m = v
                    } else if (v < m) {
                        m = v
                    }
                }
                tmp[row + x] = m
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) {
                val y0 = max(0, y - r)
                val y1 = min(h - 1, y + r)
                var m = tmp[y0 * w + x]
                for (k in y0 + 1..y1) {
                    val v = tmp[k * w + x]
                    if (isMax) {
                        if (v > m) m = v
                    } else if (v < m) {
                        m = v
                    }
                }
                out[y * w + x] = m
            }
        }
        return out
    }

    /** Kastenweichzeichner (in place). */
    private fun boxBlur(a: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            var cnt = 0
            for (x in 0..min(r, w - 1)) {
                sum += a[row + x]; cnt++
            }
            for (x in 0 until w) {
                tmp[row + x] = sum / cnt
                val add = x + r + 1
                val rem = x - r
                if (add < w) {
                    sum += a[row + add]; cnt++
                }
                if (rem >= 0) {
                    sum -= a[row + rem]; cnt--
                }
            }
        }
        for (x in 0 until w) {
            var sum = 0
            var cnt = 0
            for (y in 0..min(r, h - 1)) {
                sum += tmp[y * w + x]; cnt++
            }
            for (y in 0 until h) {
                a[y * w + x] = sum / cnt
                val add = y + r + 1
                val rem = y - r
                if (add < h) {
                    sum += tmp[add * w + x]; cnt++
                }
                if (rem >= 0) {
                    sum -= tmp[rem * w + x]; cnt--
                }
            }
        }
    }

    /** Füllt Löcher einer Binärmaske (alles, was vom Rand aus nicht erreichbar ist, wird 1). */
    private fun fillHoles(m: IntArray, w: Int, h: Int) {
        val outside = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var tail = 0
        fun push(i: Int) {
            if (m[i] == 0 && !outside[i]) {
                outside[i] = true
                queue[tail++] = i
            }
        }
        for (x in 0 until w) {
            push(x); push((h - 1) * w + x)
        }
        for (y in 0 until h) {
            push(y * w); push(y * w + w - 1)
        }
        var head = 0
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }
        for (i in 0 until w * h) if (!outside[i]) m[i] = 1
    }

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
        // Ein neues Lasso ersetzt die automatisch erkannte Region
        for (i in 0 until w * h) {
            val a = m[i] ushr 24
            keep[i] = a.toByte()
            force[i] = ((force[i].toInt() and 0xFF) * a / 255).toByte()
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

        /**
         * Komplett automatisches Freistellen ohne Benutzeroberfläche (für den Mehrfach-Import).
         * Liefert ein zugeschnittenes Bitmap mit Transparenz oder null.
         */
        fun autoExtract(src: Bitmap, hints: AiHints? = null): Bitmap? {
            val ex = Extractor(src)
            val auto = ex.autoDetect(hints)
            ex.applyAuto(auto)
            ex.applyKey(ex.computeKey(ex.params()))
            ex.composeAll()
            val trimmed = ImageUtils.trimTransparent(ex.result)
            if (trimmed !== ex.result) ex.result.recycle()
            return trimmed
        }
    }
}
