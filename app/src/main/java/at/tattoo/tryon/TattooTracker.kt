package at.tattoo.tryon

import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.CLAHE
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Verfolgt die angetippte Körperstelle im Kamerabild (OpenCV).
 *
 * Wichtig: Die Lage wird NICHT von Bild zu Bild aufaddiert (das sammelt Verzerrungen an,
 * die beim Zurückdrehen bleiben). Stattdessen merkt sich jeder verfolgte Punkt seine Position
 * im Referenzbild (Moment des Antippens). Pro Bild wird die Homographie Referenz -> jetzt
 * neu geschätzt. Dreht sich der Körper zurück, kehrt auch das Tattoo exakt in seine
 * Ausgangsform zurück.
 */
class TattooTracker(private val listener: Listener) : ImageAnalysis.Analyzer {

    interface Listener {
        /** Abbildung Referenz-View-Koordinaten -> aktuelle View-Koordinaten. Analyse-Thread! */
        fun onTransform(refToCurrent: Matrix, generation: Int)

        /** Tracking-Qualität geändert (true = stabil). Analyse-Thread! */
        fun onTrackingState(stable: Boolean)

        /** Aktuelles Tattoo-Viereck (8 Werte, View-Koordinaten) oder null. Analyse-Thread! */
        fun currentQuad(): FloatArray?

        /** Tattoo-Viereck im Referenzbild (View-Koordinaten) oder null. Analyse-Thread! */
        fun referenceQuad(): FloatArray?

        /** Aktuell verfolgte Punkte (View-Koordinaten, x/y abwechselnd). Analyse-Thread! */
        fun onPoints(points: FloatArray) {}
    }

    @Volatile
    var active = false
        set(value) {
            field = value
            if (value) restart()
        }

    /** Wird bei jedem Neustart erhöht; alte Ergebnisse werden so verworfen. */
    @Volatile
    var generation = 0
        private set

    @Volatile
    private var sensorToView: Matrix? = null

    @Volatile
    private var restartRequested = true

    @Volatile
    private var reseedRequested = true

    private var prevGray: Mat? = null
    private var curPts = ArrayList<Point>()
    private var refPts = ArrayList<Point>()

    /** Homographie Referenz -> aktuell (Puffer-Koordinaten). */
    private val hCur = Matrix()
    private var bytes = ByteArray(0)
    private var failures = 0
    private var lastStable: Boolean? = null
    private var frameCount = 0

    // Hautfarbe (Cb/Cr) an der angetippten Stelle: Merkmale nur auf dieser Körperstelle,
    // nicht im Hintergrund (sonst "gewinnt" bei einer Drehung der stillstehende Hintergrund).
    private var refCb = -1f
    private var refCr = -1f
    private var cbBytes = ByteArray(0)
    private var crBytes = ByteArray(0)
    private var clahe: CLAHE? = null

    override fun getTargetCoordinateSystem(): Int = CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED

    override fun updateTransform(matrix: Matrix?) {
        sensorToView = matrix?.let { Matrix(it) }
    }

    /** Zusätzliche Merkmale im aktuellen Tattoo-Bereich suchen (z. B. nach Verschieben). */
    fun requestReseed() {
        reseedRequested = true
    }

    /** Neues Referenzbild: das aktuelle Bild wird zur Ausgangslage. Gibt die neue Generation zurück. */
    fun restart(): Int {
        generation++
        restartRequested = true
        reseedRequested = true
        return generation
    }

    override fun analyze(image: ImageProxy) {
        try {
            process(image)
        } catch (t: Throwable) {
            // Einzelnes Bild überspringen
        } finally {
            image.close()
        }
    }

    private fun process(image: ImageProxy) {
        if (restartRequested) {
            releaseState()
            restartRequested = false
            failures = 0
            refCb = -1f
            refCr = -1f
            hCur.reset()
        }
        if (!active) {
            releaseState()
            return
        }
        val gen = generation
        val s2v = sensorToView ?: return
        val s2b = image.imageInfo.sensorToBufferTransformMatrix
        val b2s = Matrix()
        if (!s2b.invert(b2s)) return
        val b2v = Matrix(b2s)
        b2v.postConcat(s2v)
        val v2b = Matrix()
        if (!b2v.invert(v2b)) return

        val gray = toGray(image)
        val cl = clahe ?: Imgproc.createCLAHE(2.5, Size(8.0, 8.0)).also { clahe = it }
        cl.apply(gray, gray)
        frameCount++

        // ---------- 1. Punkte vom letzten Bild ins aktuelle verfolgen ----------
        val prev = prevGray
        if (prev != null && curPts.size >= 6 && prev.cols() == gray.cols() && prev.rows() == gray.rows()) {
            val prevM = MatOfPoint2f(*curPts.toTypedArray())
            val next = MatOfPoint2f()
            val status = MatOfByte()
            val err = MatOfFloat()
            Video.calcOpticalFlowPyrLK(prev, gray, prevM, next, status, err, Size(21.0, 21.0), 3)
            val back = MatOfPoint2f()
            val status2 = MatOfByte()
            val err2 = MatOfFloat()
            Video.calcOpticalFlowPyrLK(gray, prev, next, back, status2, err2, Size(21.0, 21.0), 3)
            val p0 = curPts
            val p1 = next.toArray()
            val pb = back.toArray()
            val st = status.toArray()
            val st2 = status2.toArray()
            prevM.release(); next.release(); status.release(); err.release()
            back.release(); status2.release(); err2.release()

            val w = gray.cols().toDouble()
            val h = gray.rows().toDouble()
            val newCur = ArrayList<Point>()
            val newRef = ArrayList<Point>()
            for (i in p0.indices) {
                if (i >= st.size || i >= st2.size || i >= p1.size || i >= pb.size) continue
                if (st[i].toInt() != 1 || st2[i].toInt() != 1) continue
                val ex = pb[i].x - p0[i].x
                val ey = pb[i].y - p0[i].y
                if (ex * ex + ey * ey > 1.0) continue
                val q = p1[i]
                if (q.x < 0 || q.y < 0 || q.x >= w || q.y >= h) continue
                newCur.add(q)
                newRef.add(refPts[i])
            }
            curPts = newCur
            refPts = newRef
        } else if (prev != null) {
            curPts = ArrayList()
            refPts = ArrayList()
        }

        // ---------- 2. Homographie Referenz -> jetzt ----------
        if (curPts.size >= 8) {
            val refQuadB = listener.referenceQuad()?.copyOf()?.also { v2b.mapPoints(it) }
            val est = estimate(refPts, curPts, refQuadB, gray.cols().toDouble(), gray.rows().toDouble())
            if (est != null) {
                hCur.set(est.first)
                val inl = est.second
                val c2 = ArrayList<Point>()
                val r2 = ArrayList<Point>()
                for (i in curPts.indices) if (i >= inl.size || inl[i]) {
                    c2.add(curPts[i]); r2.add(refPts[i])
                }
                curPts = c2
                refPts = r2
                failures = 0
                val tv = Matrix(v2b)
                tv.postConcat(hCur)
                tv.postConcat(b2v)
                listener.onTransform(tv, gen)
            } else {
                failures++
            }
        } else if (frameCount > 1 && prev != null) {
            failures++
        }

        // ---------- 3. Neue Punkte ergänzen (Referenzlage über die Umkehrabbildung) ----------
        if (reseedRequested || curPts.size < 60 || (frameCount % 15 == 0 && curPts.size < 220)) {
            val added = seed(gray, v2b, image)
            if (added.isNotEmpty()) {
                val inv = Matrix()
                if (hCur.invert(inv)) {
                    val f = FloatArray(added.size * 2)
                    for (i in added.indices) {
                        f[i * 2] = added[i].x.toFloat()
                        f[i * 2 + 1] = added[i].y.toFloat()
                    }
                    inv.mapPoints(f)
                    for (i in added.indices) {
                        if (curPts.size >= 300) break
                        curPts.add(added[i])
                        refPts.add(Point(f[i * 2].toDouble(), f[i * 2 + 1].toDouble()))
                    }
                    reseedRequested = false
                }
            }
        }

        val stable = failures < 8 && curPts.size >= 8
        if (stable != lastStable) {
            lastStable = stable
            listener.onTrackingState(stable)
        }

        if (curPts.isNotEmpty()) {
            val f = FloatArray(curPts.size * 2)
            for (i in curPts.indices) {
                f[i * 2] = curPts[i].x.toFloat()
                f[i * 2 + 1] = curPts[i].y.toFloat()
            }
            b2v.mapPoints(f)
            listener.onPoints(f)
        }

        prevGray?.release()
        prevGray = gray
    }

    /**
     * Schätzt Referenz -> aktuell. Zuerst volle Homographie (Perspektive), sonst
     * Ähnlichkeitstransformation. Prüft, ob das Tattoo-Viereck dabei plausibel bleibt.
     */
    private fun estimate(
        ref: List<Point>, cur: List<Point>, refQuad: FloatArray?, w: Double, h: Double
    ): Pair<Matrix, BooleanArray>? {
        val srcM = MatOfPoint2f(*ref.toTypedArray())
        val dstM = MatOfPoint2f(*cur.toTypedArray())
        try {
            val mask = Mat()
            val hom = Calib3d.findHomography(srcM, dstM, Calib3d.RANSAC, 3.0, mask)
            if (!hom.empty()) {
                val m = toMatrix3(hom)
                val inl = inliers(mask, cur.size)
                hom.release(); mask.release()
                if (inl.count { it } >= 8 && quadOk(m, refQuad, w, h)) return Pair(m, inl)
            } else {
                hom.release(); mask.release()
            }
            val inlM = Mat()
            val aff = Calib3d.estimateAffinePartial2D(srcM, dstM, inlM, Calib3d.RANSAC, 3.0)
            if (!aff.empty()) {
                val m = toMatrixAffine(aff)
                val inl = inliers(inlM, cur.size)
                aff.release(); inlM.release()
                if (inl.count { it } >= 6 && quadOk(m, refQuad, w, h)) return Pair(m, inl)
            } else {
                aff.release(); inlM.release()
            }
            return null
        } finally {
            srcM.release(); dstM.release()
        }
    }

    private fun inliers(mask: Mat, n: Int): BooleanArray {
        val m = ByteArray(mask.rows() * mask.cols())
        if (m.isNotEmpty()) mask.get(0, 0, m)
        return BooleanArray(n) { i -> i >= m.size || m[i].toInt() != 0 }
    }

    /** Das abgebildete Referenz-Viereck muss konvex bleiben und darf nicht extrem verzerren. */
    private fun quadOk(m: Matrix, refQuad: FloatArray?, w: Double, h: Double): Boolean {
        val v = FloatArray(9)
        m.getValues(v)
        if (abs(v[6]) * w > 1.2 || abs(v[7]) * h > 1.2) return false
        if (refQuad == null) return true
        val q = refQuad.copyOf()
        m.mapPoints(q)
        if (!convex(q)) return false
        val a0 = area(refQuad)
        val a1 = area(q)
        if (a0 <= 1f || a1 < a0 * 0.15f || a1 > a0 * 6f) return false
        // Seitenverhältnisse der Kanten dürfen sich nicht extrem ändern
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val l0 = hypot(refQuad[j * 2] - refQuad[i * 2], refQuad[j * 2 + 1] - refQuad[i * 2 + 1])
            val l1 = hypot(q[j * 2] - q[i * 2], q[j * 2 + 1] - q[i * 2 + 1])
            if (l0 > 1f && (l1 / l0 < 0.2f || l1 / l0 > 5f)) return false
        }
        return true
    }

    /** Sucht zusätzliche gut verfolgbare Punkte auf der Körperstelle rund um das Tattoo. */
    private fun seed(gray: Mat, v2b: Matrix, image: ImageProxy): List<Point> {
        val quad = listener.currentQuad() ?: return emptyList()
        val pts = quad.copyOf()
        v2b.mapPoints(pts)
        var cx = 0f
        var cy = 0f
        for (i in 0 until 4) {
            cx += pts[i * 2]; cy += pts[i * 2 + 1]
        }
        cx /= 4f; cy /= 4f
        val grow = 2.0f
        val poly = Array(4) { i ->
            Point(
                (cx + (pts[i * 2] - cx) * grow).toDouble(),
                (cy + (pts[i * 2 + 1] - cy) * grow).toDouble()
            )
        }
        val roi = Mat.zeros(gray.size(), CvType.CV_8UC1)
        val polyM = MatOfPoint(*poly)
        Imgproc.fillConvexPoly(roi, polyM, Scalar(255.0))
        polyM.release()

        var mask = roi
        val skin = try {
            skinMask(image, cx, cy)
        } catch (t: Throwable) {
            null
        }
        if (skin != null) {
            val combined = Mat()
            Core.bitwise_and(skin, roi, combined)
            skin.release()
            if (Core.countNonZero(combined) > 1500) {
                mask = combined
                roi.release()
            } else {
                combined.release()
            }
        }
        // Nicht direkt neben bereits verfolgten Punkten suchen
        for (p in curPts) Imgproc.circle(mask, p, 7, Scalar(0.0), -1)

        val corners = MatOfPoint()
        val want = max(20, 260 - curPts.size)
        Imgproc.goodFeaturesToTrack(gray, corners, want, 0.002, 6.0, mask, 5, false, 0.04)
        mask.release()
        val arr = corners.toArray()
        corners.release()
        return arr.map { Point(it.x, it.y) }
    }

    /**
     * Maske der Körperstelle: Pixel mit ähnlicher Hautfarbe (Cb/Cr) wie an der angetippten Stelle,
     * zusammenhängend mit dieser Stelle. Volle Puffer-Auflösung, 255 = Haut.
     */
    private fun skinMask(image: ImageProxy, cx: Float, cy: Float): Mat? {
        val w = image.width
        val h = image.height
        val cw = w / 2
        val ch = h / 2
        if (cw < 4 || ch < 4) return null
        if (cbBytes.size != cw * ch) {
            cbBytes = ByteArray(cw * ch)
            crBytes = ByteArray(cw * ch)
        }
        readChroma(image.planes[1], cw, ch, cbBytes)
        readChroma(image.planes[2], cw, ch, crBytes)

        // Referenz-Hautfarbe rund um die Tattoo-Mitte
        val ccx = (cx / 2f).toInt().coerceIn(0, cw - 1)
        val ccy = (cy / 2f).toInt().coerceIn(0, ch - 1)
        val r = 5
        var sCb = 0f
        var sCr = 0f
        var cnt = 0
        for (y in (ccy - r).coerceAtLeast(0)..(ccy + r).coerceAtMost(ch - 1)) {
            for (x in (ccx - r).coerceAtLeast(0)..(ccx + r).coerceAtMost(cw - 1)) {
                sCb += (cbBytes[y * cw + x].toInt() and 0xFF)
                sCr += (crBytes[y * cw + x].toInt() and 0xFF)
                cnt++
            }
        }
        if (cnt == 0) return null
        val curCb = sCb / cnt
        val curCr = sCr / cnt
        if (refCb < 0f) {
            refCb = curCb
            refCr = curCr
        } else if (kotlin.math.abs(curCb - refCb) < 20f && kotlin.math.abs(curCr - refCr) < 20f) {
            // Langsam an Lichtänderungen anpassen
            refCb = refCb * 0.8f + curCb * 0.2f
            refCr = refCr * 0.8f + curCr * 0.2f
        }

        val bin = ByteArray(cw * ch)
        for (i in 0 until cw * ch) {
            val dcb = (cbBytes[i].toInt() and 0xFF) - refCb
            val dcr = (crBytes[i].toInt() and 0xFF) - refCr
            if (dcb * dcb / (14f * 14f) + dcr * dcr / (12f * 12f) <= 1f) bin[i] = -1
        }
        val small = Mat(ch, cw, CvType.CV_8UC1)
        small.put(0, 0, bin)
        val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        val k7 = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
        Imgproc.morphologyEx(small, small, Imgproc.MORPH_OPEN, k3)
        Imgproc.morphologyEx(small, small, Imgproc.MORPH_CLOSE, k7)

        // Zusammenhängender Bereich, der die Tattoo-Mitte enthält
        val labels = Mat()
        Imgproc.connectedComponents(small, labels, 8, CvType.CV_32S)
        val lab = labels.get(ccy, ccx)?.getOrNull(0)?.toInt() ?: 0
        small.release()
        if (lab <= 0) {
            labels.release(); k3.release(); k7.release()
            return null
        }
        val comp = Mat()
        Core.compare(labels, Scalar(lab.toDouble()), comp, Core.CMP_EQ)
        labels.release()
        val full = Mat()
        Imgproc.resize(comp, full, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
        comp.release()
        // Rand etwas abtragen, damit keine Hintergrund-Kanten mitverfolgt werden
        Imgproc.erode(full, full, k7)
        k3.release(); k7.release()
        return full
    }

    private fun readChroma(plane: ImageProxy.PlaneProxy, cw: Int, ch: Int, out: ByteArray) {
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val limit = buf.limit()
        for (y in 0 until ch) {
            val row = y * rs
            for (x in 0 until cw) {
                val idx = row + x * ps
                out[y * cw + x] = if (idx < limit) buf.get(idx) else 0
            }
        }
    }

    private fun toGray(image: ImageProxy): Mat {
        val plane = image.planes[0]
        val buf = plane.buffer
        val w = image.width
        val h = image.height
        val rowStride = plane.rowStride
        if (bytes.size != w * h) bytes = ByteArray(w * h)
        buf.rewind()
        if (rowStride == w) {
            buf.get(bytes, 0, w * h)
        } else {
            for (y in 0 until h) {
                buf.position(y * rowStride)
                buf.get(bytes, y * w, w)
            }
        }
        val mat = Mat(h, w, CvType.CV_8UC1)
        mat.put(0, 0, bytes)
        return mat
    }

    private fun toMatrix3(hom: Mat): Matrix {
        val v = FloatArray(9)
        val h22 = hom.get(2, 2)[0]
        for (r in 0 until 3) for (c in 0 until 3) v[r * 3 + c] = (hom.get(r, c)[0] / h22).toFloat()
        return Matrix().apply { setValues(v) }
    }

    private fun toMatrixAffine(aff: Mat): Matrix {
        val v = FloatArray(9)
        for (r in 0 until 2) for (c in 0 until 3) v[r * 3 + c] = aff.get(r, c)[0].toFloat()
        v[6] = 0f; v[7] = 0f; v[8] = 1f
        return Matrix().apply { setValues(v) }
    }

    private fun releaseState() {
        prevGray?.release()
        prevGray = null
        curPts = ArrayList()
        refPts = ArrayList()
        lastStable = null
    }

    companion object {
        fun area(q: FloatArray): Float {
            var a = 0f
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                a += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1]
            }
            return abs(a) / 2f
        }

        fun convex(q: FloatArray): Boolean {
            var sign = 0
            for (i in 0 until 4) {
                val ax = q[((i + 1) % 4) * 2] - q[i * 2]
                val ay = q[((i + 1) % 4) * 2 + 1] - q[i * 2 + 1]
                val bx = q[((i + 2) % 4) * 2] - q[((i + 1) % 4) * 2]
                val by = q[((i + 2) % 4) * 2 + 1] - q[((i + 1) % 4) * 2 + 1]
                val cross = ax * by - ay * bx
                val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
                if (s != 0) {
                    if (sign == 0) sign = s else if (s != sign) return false
                }
            }
            return true
        }
    }
}
