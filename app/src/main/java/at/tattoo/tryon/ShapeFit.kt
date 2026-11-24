package at.tattoo.tryon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Passt das Tattoo an die Körperstelle im Foto an.
 *
 * Aus dem Foto werden drei "Höhenkarten" geschätzt:
 *  1. Form/Rundung: Silhouette der Körperstelle (Hautbereich) -> Abstand zum Rand
 *     ergibt ein zylinderartiges Profil (Mitte hoch, Ränder fallen ab).
 *  2. Schattierung: großräumige Helligkeit (Licht fällt auf Wölbungen) als Hinweis auf Höhen.
 *  3. Falten: feine Helligkeitsunterschiede (Band-Pass) = Höhen und Tiefen von Falten.
 * Aus dem Gefälle dieser Karten wird eine Verschiebungskarte berechnet, mit der das Tattoo
 * verzerrt wird (wie ein Displacement-Map-Filter). Zusätzlich wird das Tattoo an der
 * Silhouette ausgeblendet und in Falten etwas abgedunkelt.
 */
object ShapeFit {

    class Result(val layer: Bitmap, val layerToBody: Matrix)

    private const val MAX_SIDE = 720f

    fun compute(body: Bitmap, tattoo: Bitmap, tattooToBody: Matrix, form: Float, folds: Float): Result? {
        val corners = floatArrayOf(
            0f, 0f, tattoo.width.toFloat(), 0f,
            tattoo.width.toFloat(), tattoo.height.toFloat(), 0f, tattoo.height.toFloat()
        )
        tattooToBody.mapPoints(corners)
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until 4) {
            minX = min(minX, corners[i * 2]); maxX = max(maxX, corners[i * 2])
            minY = min(minY, corners[i * 2 + 1]); maxY = max(maxY, corners[i * 2 + 1])
        }
        val tbw = maxX - minX
        val tbh = maxY - minY
        if (tbw < 4f || tbh < 4f) return null
        // Rand um das Tattoo, damit die Silhouette der Körperstelle erfasst wird
        val padX = tbw * 0.4f
        val padY = tbh * 0.4f
        val x0 = floor(minX - padX).toInt().coerceIn(0, body.width - 1)
        val y0 = floor(minY - padY).toInt().coerceIn(0, body.height - 1)
        val x1 = ceil(maxX + padX).toInt().coerceIn(x0 + 1, body.width)
        val y1 = ceil(maxY + padY).toInt().coerceIn(y0 + 1, body.height)
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw < 8 || rh < 8) return null

        val s = min(1f, MAX_SIDE / max(rw, rh))
        val w = max(8, (rw * s).roundToInt())
        val h = max(8, (rh * s).roundToInt())
        val sx = w.toFloat() / rw
        val sy = h.toFloat() / rh
        val n = w * h

        // Fotoausschnitt
        val crop = Bitmap.createBitmap(body, x0, y0, rw, rh)
        val small = Bitmap.createScaledBitmap(crop, w, h, true)
        val bp = IntArray(n)
        small.getPixels(bp, 0, w, 0, 0, w, h)
        if (small !== crop) small.recycle()
        if (crop !== body) crop.recycle()

        // Tattoo als Ebene im selben Ausschnitt
        val layerBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val lm = Matrix(tattooToBody)
        lm.postTranslate(-x0.toFloat(), -y0.toFloat())
        lm.postScale(sx, sy)
        Canvas(layerBmp).drawBitmap(tattoo, lm, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        val lp = IntArray(n)
        layerBmp.getPixels(lp, 0, w, 0, 0, w, h)
        layerBmp.recycle()

        // Bereich des Tattoos (ohne Rand) im Ausschnitt
        val ix0 = ((minX - x0) * sx).toInt().coerceIn(0, w - 1)
        val iy0 = ((minY - y0) * sy).toInt().coerceIn(0, h - 1)
        val ix1 = ((maxX - x0) * sx).toInt().coerceIn(ix0 + 1, w)
        val iy1 = ((maxY - y0) * sy).toInt().coerceIn(iy0 + 1, h)
        val tSize = min(ix1 - ix0, iy1 - iy0).toFloat().coerceAtLeast(8f)

        // Helligkeit
        val lum = FloatArray(n) {
            val c = bp[it]
            (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f
        }

        // ---------- 1. Silhouette / Rundung ----------
        val skin = skinMedian(bp, w, ix0, iy0, ix1, iy1)
        val dist = FloatArray(n)
        for (i in 0 until n) {
            val c = bp[i]
            val dr = ((c shr 16) and 0xFF) - Color.red(skin)
            val dg = ((c shr 8) and 0xFF) - Color.green(skin)
            val db = (c and 0xFF) - Color.blue(skin)
            // Helligkeit weniger gewichten (Schatten gehören noch zur Haut)
            val lumD = (dr + dg + db) / 3f
            val cr = dr - lumD
            val cg = dg - lumD
            val cb = db - lumD
            dist[i] = sqrt(cr * cr + cg * cg + cb * cb) * 1.6f + kotlin.math.abs(lumD) * 0.45f
        }
        val innerD = ArrayList<Float>()
        for (y in iy0 until iy1 step 2) for (x in ix0 until ix1 step 2) innerD.add(dist[y * w + x])
        innerD.sort()
        val medD = if (innerD.isEmpty()) 20f else innerD[innerD.size / 2]
        val thr = max(30f, medD * 3f)
        var mask = IntArray(n) { if (dist[it] < thr) 1 else 0 }
        val mr = max(1, (min(w, h) * 0.015f).toInt())
        mask = minMax(minMax(mask, w, h, mr, true), w, h, mr, false) // schließen
        mask = minMax(minMax(mask, w, h, mr, false), w, h, mr, true) // öffnen
        val body1 = componentUnderTattoo(mask, lp, w, h)

        // Abstand zum Rand der Körperstelle (Chamfer-Distanz). Bildrand zählt nicht als Körperrand.
        val far = 1e9f
        val dt = FloatArray(n) { if (body1[it] == 1) far else 0f }
        chamfer(dt, w, h)
        val finite = ArrayList<Float>()
        for (i in 0 until n step 3) if (body1[i] == 1 && dt[i] < far / 2) finite.add(dt[i])
        finite.sort()
        val limbHalf = if (finite.size > 20) max(4f, finite[(finite.size * 0.97f).toInt().coerceAtMost(finite.size - 1)]) else -1f
        val hForm = FloatArray(n)
        for (i in 0 until n) {
            hForm[i] = when {
                body1[i] == 0 -> 0f
                limbHalf <= 0f || dt[i] >= far / 2 -> 1f
                else -> {
                    val t = min(dt[i] / limbHalf, 1f)
                    sqrt(max(0f, 1f - (1f - t) * (1f - t)))
                }
            }
        }
        boxBlur(hForm, w, h, max(1, (min(w, h) * 0.01f).toInt()))
        val maskSoft = FloatArray(n) { body1[it].toFloat() }
        boxBlur(maskSoft, w, h, max(1, (min(w, h) * 0.006f).toInt()))

        // ---------- 2. Schattierung ----------
        val hShade = lum.copyOf()
        boxBlur(hShade, w, h, max(2, (tSize * 0.08f).toInt()))

        // ---------- 3. Falten (Band-Pass) ----------
        val fine = lum.copyOf()
        boxBlur(fine, w, h, max(1, (tSize * 0.006f).toInt()))
        val coarse = lum.copyOf()
        boxBlur(coarse, w, h, max(3, (tSize * 0.04f).toInt()))
        val hFold = FloatArray(n) { fine[it] - coarse[it] }

        // Gradienten, robust normiert
        val gfx = FloatArray(n); val gfy = FloatArray(n)
        val gsx = FloatArray(n); val gsy = FloatArray(n)
        val gkx = FloatArray(n); val gky = FloatArray(n)
        gradient(hForm, w, h, gfx, gfy)
        gradient(hShade, w, h, gsx, gsy)
        gradient(hFold, w, h, gkx, gky)
        val nf = robustMax(gfx, gfy, w, ix0, iy0, ix1, iy1)
        val ns = robustMax(gsx, gsy, w, ix0, iy0, ix1, iy1)
        val nk = robustMax(gkx, gky, w, ix0, iy0, ix1, iy1)
        val foldNorm = robustAbs(hFold, w, ix0, iy0, ix1, iy1)

        val ampForm = form * 0.09f * tSize
        val ampShade = form * 0.035f * tSize
        val ampFold = folds * (0.018f * tSize + 1.5f)

        val out = IntArray(n)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val dx = -(ampForm * gfx[i] / nf + ampShade * gsx[i] / ns + ampFold * gkx[i] / nk)
                val dy = -(ampForm * gfy[i] / nf + ampShade * gsy[i] / ns + ampFold * gky[i] / nk)
                val c = sample(lp, w, h, x + dx, y + dy)
                var a = (c ushr 24).toFloat()
                if (a < 1f) continue
                // An der Silhouette ausblenden, zur Seite hin leicht blasser
                a *= 1f - form * (1f - maskSoft[i])
                a *= 1f - 0.45f * form * (1f - hForm[i])
                // In Falten (dunkler) die Tinte etwas abdunkeln, auf Erhebungen aufhellen
                val shade = (1f + folds * 0.9f * (hFold[i] / foldNorm)).coerceIn(0.55f, 1.25f)
                val r = (((c shr 16) and 0xFF) * shade).toInt().coerceIn(0, 255)
                val g = (((c shr 8) and 0xFF) * shade).toInt().coerceIn(0, 255)
                val b = ((c and 0xFF) * shade).toInt().coerceIn(0, 255)
                out[i] = (a.toInt().coerceIn(0, 255) shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val layer = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        val toBody = Matrix()
        toBody.setScale(1f / sx, 1f / sy)
        toBody.postTranslate(x0.toFloat(), y0.toFloat())
        return Result(layer, toBody)
    }

    /** Durchschnittliche Hautfarbe rund um einen Punkt des Fotos (für die Farbangleichung). */
    fun skinAround(body: Bitmap, cx: Int, cy: Int, radius: Int): Int {
        val x0 = (cx - radius).coerceIn(0, body.width - 1)
        val y0 = (cy - radius).coerceIn(0, body.height - 1)
        val x1 = (cx + radius).coerceIn(x0 + 1, body.width)
        val y1 = (cy + radius).coerceIn(y0 + 1, body.height)
        val w = x1 - x0
        val h = y1 - y0
        val px = IntArray(w * h)
        body.getPixels(px, 0, w, x0, y0, w, h)
        return medianColor(px)
    }

    // ---------- Hilfsfunktionen ----------

    private fun skinMedian(bp: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        val list = ArrayList<Int>()
        for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) list.add(bp[y * w + x])
        return medianColor(list.toIntArray())
    }

    private fun medianColor(px: IntArray): Int {
        if (px.isEmpty()) return Color.rgb(200, 160, 140)
        val r = IntArray(px.size) { (px[it] shr 16) and 0xFF }
        val g = IntArray(px.size) { (px[it] shr 8) and 0xFF }
        val b = IntArray(px.size) { px[it] and 0xFF }
        r.sort(); g.sort(); b.sort()
        val m = px.size / 2
        return Color.rgb(r[m], g[m], b[m])
    }

    /** Wählt den Hautbereich, der am meisten unter dem Tattoo liegt. */
    private fun componentUnderTattoo(mask: IntArray, lp: IntArray, w: Int, h: Int): IntArray {
        val n = w * h
        val labels = IntArray(n) { -1 }
        val queue = IntArray(n)
        var best = -1
        var bestOverlap = 0
        var label = 0
        for (start in 0 until n) {
            if (mask[start] == 0 || labels[start] != -1) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = label
            var overlap = 0
            while (head < tail) {
                val i = queue[head++]
                if ((lp[i] ushr 24) > 20) overlap++
                val x = i % w
                val y = i / w
                if (x > 0) { val j = i - 1; if (mask[j] == 1 && labels[j] == -1) { labels[j] = label; queue[tail++] = j } }
                if (x < w - 1) { val j = i + 1; if (mask[j] == 1 && labels[j] == -1) { labels[j] = label; queue[tail++] = j } }
                if (y > 0) { val j = i - w; if (mask[j] == 1 && labels[j] == -1) { labels[j] = label; queue[tail++] = j } }
                if (y < h - 1) { val j = i + w; if (mask[j] == 1 && labels[j] == -1) { labels[j] = label; queue[tail++] = j } }
            }
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                best = label
            }
            label++
        }
        return if (best < 0) IntArray(n) { 1 } else IntArray(n) { if (labels[it] == best) 1 else 0 }
    }

    /** Zwei-Pass-Chamfer-Distanz (in place). Pixel mit 0 sind Rand/außen. */
    private fun chamfer(d: FloatArray, w: Int, h: Int) {
        val diag = 1.4142f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                var v = d[i]
                if (v == 0f) continue
                if (x > 0) v = min(v, d[i - 1] + 1f)
                if (y > 0) {
                    v = min(v, d[i - w] + 1f)
                    if (x > 0) v = min(v, d[i - w - 1] + diag)
                    if (x < w - 1) v = min(v, d[i - w + 1] + diag)
                }
                d[i] = v
            }
        }
        for (y in h - 1 downTo 0) {
            for (x in w - 1 downTo 0) {
                val i = y * w + x
                var v = d[i]
                if (v == 0f) continue
                if (x < w - 1) v = min(v, d[i + 1] + 1f)
                if (y < h - 1) {
                    v = min(v, d[i + w] + 1f)
                    if (x < w - 1) v = min(v, d[i + w + 1] + diag)
                    if (x > 0) v = min(v, d[i + w - 1] + diag)
                }
                d[i] = v
            }
        }
    }

    private fun gradient(f: FloatArray, w: Int, h: Int, gx: FloatArray, gy: FloatArray) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val l = f[if (x > 0) i - 1 else i]
                val r = f[if (x < w - 1) i + 1 else i]
                val u = f[if (y > 0) i - w else i]
                val d = f[if (y < h - 1) i + w else i]
                gx[i] = (r - l) * 0.5f
                gy[i] = (d - u) * 0.5f
            }
        }
    }

    /** 98-%-Wert der Gradientenlänge im Tattoo-Bereich (verhindert Ausreißer). */
    private fun robustMax(gx: FloatArray, gy: FloatArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): Float {
        val list = ArrayList<Float>()
        for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) {
            val i = y * w + x
            list.add(sqrt(gx[i] * gx[i] + gy[i] * gy[i]))
        }
        if (list.isEmpty()) return 1f
        list.sort()
        return max(list[(list.size * 0.98f).toInt().coerceAtMost(list.size - 1)], 1e-4f)
    }

    private fun robustAbs(f: FloatArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): Float {
        val list = ArrayList<Float>()
        for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) list.add(kotlin.math.abs(f[y * w + x]))
        if (list.isEmpty()) return 1f
        list.sort()
        return max(list[(list.size * 0.98f).toInt().coerceAtMost(list.size - 1)], 1e-3f)
    }

    /** Bilineares Abtasten mit vormultipliziertem Alpha (keine dunklen Ränder). */
    private fun sample(px: IntArray, w: Int, h: Int, fx: Float, fy: Float): Int {
        if (fx < 0f || fy < 0f || fx > w - 1 || fy > h - 1) return 0
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(w - 1, x0 + 1)
        val y1 = min(h - 1, y0 + 1)
        val ax = fx - x0
        val ay = fy - y0
        val c00 = px[y0 * w + x0]
        val c10 = px[y0 * w + x1]
        val c01 = px[y1 * w + x0]
        val c11 = px[y1 * w + x1]
        val w00 = (1 - ax) * (1 - ay) * (c00 ushr 24)
        val w10 = ax * (1 - ay) * (c10 ushr 24)
        val w01 = (1 - ax) * ay * (c01 ushr 24)
        val w11 = ax * ay * (c11 ushr 24)
        val a = w00 + w10 + w01 + w11
        if (a < 0.5f) return 0
        val r = (((c00 shr 16) and 0xFF) * w00 + ((c10 shr 16) and 0xFF) * w10 + ((c01 shr 16) and 0xFF) * w01 + ((c11 shr 16) and 0xFF) * w11) / a
        val g = (((c00 shr 8) and 0xFF) * w00 + ((c10 shr 8) and 0xFF) * w10 + ((c01 shr 8) and 0xFF) * w01 + ((c11 shr 8) and 0xFF) * w11) / a
        val b = ((c00 and 0xFF) * w00 + (c10 and 0xFF) * w10 + (c01 and 0xFF) * w01 + (c11 and 0xFF) * w11) / a
        return (a.toInt().coerceIn(0, 255) shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or
            (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
    }

    /** Kastenweichzeichner für FloatArray (in place). */
    private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int) {
        val tmp = FloatArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
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
            var sum = 0f
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

    /** Separierbarer Max- bzw. Min-Filter für Binärmasken. */
    private fun minMax(src: IntArray, w: Int, h: Int, r: Int, isMax: Boolean): IntArray {
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var m = src[row + x]
                for (k in max(0, x - r)..min(w - 1, x + r)) {
                    val v = src[row + k]
                    if (isMax) { if (v > m) m = v } else if (v < m) m = v
                }
                tmp[row + x] = m
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) {
                var m = tmp[y * w + x]
                for (k in max(0, y - r)..min(h - 1, y + r)) {
                    val v = tmp[k * w + x]
                    if (isMax) { if (v > m) m = v } else if (v < m) m = v
                }
                out[y * w + x] = m
            }
        }
        return out
    }
}
