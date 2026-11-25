package at.tattoo.tryon

import android.content.Context
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.CLAHE
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
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
class TattooTracker(context: Context, private val listener: Listener) : ImageAnalysis.Analyzer {

    private val appContext: Context = context.applicationContext

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

        /**
         * Erkannte Körperform an der Tattoo-Stelle (View-Koordinaten):
         * [Achsenpunkt x, y, Achsrichtung x, y, Querrichtung x, y, halbe Breite] oder null. Analyse-Thread!
         */
        fun onLimb(limb: FloatArray?) {}
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

    // Geglättete Drehung/Größe (Verschiebung bleibt unverzögert)
    private var smInit = false
    private var smScale = 1f
    private var smAngle = 0f

    /**
     * Beruhigt Drehung und Größe (Messrauschen erzeugt sonst ein "Verdrehen" bei reinen
     * Kamera-Verschiebungen). Der Tattoo-Mittelpunkt folgt dagegen ohne Verzögerung.
     */
    private fun stabilize(raw: Matrix, refQuad: FloatArray?): Matrix {
        val v = FloatArray(9)
        raw.getValues(v)
        val scale = hypot(v[0], v[3])
        val angle = atan2(v[3], v[0])
        var cx = 0f
        var cy = 0f
        if (refQuad != null) {
            for (i in 0 until 4) {
                cx += refQuad[i * 2]; cy += refQuad[i * 2 + 1]
            }
            cx /= 4f; cy /= 4f
        } else if (refPts.isNotEmpty()) {
            for (p in refPts) {
                cx += p.x.toFloat(); cy += p.y.toFloat()
            }
            cx /= refPts.size; cy /= refPts.size
        }
        val c = floatArrayOf(cx, cy)
        raw.mapPoints(c)
        if (!smInit) {
            smInit = true
            smScale = scale
            smAngle = angle
        } else {
            var d = angle - smAngle
            val pi = Math.PI.toFloat()
            while (d > pi) d -= 2f * pi
            while (d < -pi) d += 2f * pi
            // Sehr kleine Änderungen fast ignorieren (Rauschen), größere zügig übernehmen
            val k = if (abs(d) < 0.02f) 0.15f else 0.5f
            smAngle += d * k
            val ds = scale - smScale
            smScale += ds * (if (abs(ds) < 0.01f * smScale) 0.15f else 0.5f)
        }
        val m = Matrix()
        m.setTranslate(-cx, -cy)
        m.postScale(smScale, smScale)
        m.postRotate(Math.toDegrees(smAngle.toDouble()).toFloat())
        m.postTranslate(c[0], c[1])
        return m
    }

    /** true = Körperstelle verloren, es wird nur noch über das Referenzbild gesucht. */
    private var lost = false

    // KI-Körpererkennung (MediaPipe). Hautfarbe dient nur als Notlösung ohne Modell.
    // Die KI läuft in einem eigenen Thread, damit das Tracking nicht ausgebremst wird.
    private val segExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var segmenter: BodySegmenter? = null // nur auf segExecutor benutzen

    @Volatile
    private var segState = 0 // 0 = unbekannt, 1 = verfügbar, -1 = kein Modell

    @Volatile
    private var segBusy = false

    @Volatile
    private var segLatest: BodySegmenter.Seg? = null

    @Volatile
    private var segVersion = 0
    private var segRequestFrame = -1000
    private var limbVersion = -1

    // Referenzbild (ORB-Merkmale) zum Wiederfinden und gegen Drift
    private var orb: ORB? = null
    private var matcher: DescriptorMatcher? = null
    private var keyDesc: Mat? = null
    private var keyPts = ArrayList<Point>()

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
            smInit = false
            lost = false
            keyDesc?.release()
            keyDesc = null
            keyPts = ArrayList()
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

        val refQuadB = listener.referenceQuad()?.copyOf()?.also { v2b.mapPoints(it) }
        val gw = gray.cols().toDouble()
        val gh = gray.rows().toDouble()

        // ---------- 1. Punkte vom letzten Bild ins aktuelle verfolgen ----------
        val prev = prevGray
        if (!lost && prev != null && curPts.size >= 6 && prev.cols() == gray.cols() && prev.rows() == gray.rows()) {
            val prevM = MatOfPoint2f(*curPts.toTypedArray())
            val next = MatOfPoint2f()
            val status = MatOfByte()
            val err = MatOfFloat()
            Video.calcOpticalFlowPyrLK(prev, gray, prevM, next, status, err, Size(25.0, 25.0), 4)
            val back = MatOfPoint2f()
            val status2 = MatOfByte()
            val err2 = MatOfFloat()
            Video.calcOpticalFlowPyrLK(gray, prev, next, back, status2, err2, Size(25.0, 25.0), 4)
            val p0 = curPts
            val p1 = next.toArray()
            val pb = back.toArray()
            val st = status.toArray()
            val st2 = status2.toArray()
            prevM.release(); next.release(); status.release(); err.release()
            back.release(); status2.release(); err2.release()

            val newCur = ArrayList<Point>()
            val newRef = ArrayList<Point>()
            for (i in p0.indices) {
                if (i >= st.size || i >= st2.size || i >= p1.size || i >= pb.size) continue
                if (st[i].toInt() != 1 || st2[i].toInt() != 1) continue
                val ex = pb[i].x - p0[i].x
                val ey = pb[i].y - p0[i].y
                if (ex * ex + ey * ey > 4.0) continue
                val q = p1[i]
                if (q.x < 0 || q.y < 0 || q.x >= gw || q.y >= gh) continue
                newCur.add(q)
                newRef.add(refPts[i])
            }
            curPts = newCur
            refPts = newRef
        }

        // ---------- 2. Homographie Referenz -> jetzt (aus den verfolgten Punkten) ----------
        var okThisFrame = false
        var inlierCount = 0
        if (!lost && curPts.size >= 8) {
            val est = estimate(refPts, curPts, refQuadB, gw, gh)
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
                inlierCount = c2.size
                okThisFrame = true
            }
        }
        val justStarted = prev == null
        if (okThisFrame) {
            failures = 0
        } else if (!justStarted) {
            failures++
            if (failures >= 3) {
                // Verloren: NICHT mit veralteter Lage weitermachen, sondern neu finden
                lost = true
                curPts = ArrayList()
                refPts = ArrayList()
            }
        }

        // ---------- 3. Wiederfinden / Nachkorrigieren über das Referenzbild (ORB-Merkmale) ----------
        if (keyDesc != null && (lost || frameCount % 8 == 0 || (okThisFrame && inlierCount < 20))) {
            val reloc = relocalize(gray, image, refQuadB, gw, gh)
            if (reloc != null) {
                hCur.set(reloc)
                okThisFrame = true
                failures = 0
                if (lost) {
                    lost = false
                    curPts = ArrayList()
                    refPts = ArrayList()
                } else {
                    // Referenzlage der verfolgten Punkte an die (driftfreie) Schätzung angleichen
                    val inv = Matrix()
                    if (hCur.invert(inv) && curPts.isNotEmpty()) {
                        val f = toFloats(curPts)
                        inv.mapPoints(f)
                        refPts = toPoints(f)
                    }
                }
            }
        }

        if (okThisFrame) {
            val out = stabilize(hCur, refQuadB)
            val tv = Matrix(v2b)
            tv.postConcat(out)
            tv.postConcat(b2v)
            listener.onTransform(tv, gen)
        }

        // Aktuelle Tattoo-Lage im Puffer (aus der frischen Schätzung, nicht aus der evtl. verzögerten Anzeige)
        val curQuadB: FloatArray? = if (refQuadB != null) {
            refQuadB.copyOf().also { hCur.mapPoints(it) }
        } else {
            listener.currentQuad()?.copyOf()?.also { v2b.mapPoints(it) }
        }

        // ---------- 4. Neue Punkte ergänzen – nur wenn die aktuelle Lage gesichert ist ----------
        if ((okThisFrame || justStarted) && !lost &&
            (reseedRequested || curPts.size < 60 || (frameCount % 15 == 0 && curPts.size < 220))
        ) {
            val added = if (curQuadB != null) seed(gray, curQuadB, image) else emptyList()
            if (added.isNotEmpty()) {
                val inv = Matrix()
                if (hCur.invert(inv)) {
                    val f = toFloats(added)
                    inv.mapPoints(f)
                    val refs = toPoints(f)
                    for (i in added.indices) {
                        if (curPts.size >= 300) break
                        curPts.add(added[i])
                        refPts.add(refs[i])
                    }
                    reseedRequested = false
                }
            }
        }

        // ---------- 5. Körperform (Achse + Breite des Arms/Beins) aus der KI-Maske ----------
        val s = currentSeg(image)
        val sv = segVersion
        if (sv != limbVersion) {
            limbVersion = sv
            if (s != null && curQuadB != null && !lost) {
                val limb = try {
                    measureLimb(s, curQuadB, b2v)
                } catch (t: Throwable) {
                    null
                }
                listener.onLimb(limb)
            } else {
                listener.onLimb(null)
            }
        }

        // Referenzbild für das Wiederfinden anlegen (einmal pro Verankerung)
        if (keyDesc == null && (okThisFrame || justStarted) && !lost && curQuadB != null) {
            buildKeyframe(gray, image, curQuadB)
        }

        val stable = !lost && failures < 3 && curPts.size >= 8
        if (stable != lastStable) {
            lastStable = stable
            listener.onTrackingState(stable)
        }

        if (curPts.isNotEmpty()) {
            val f = toFloats(curPts)
            b2v.mapPoints(f)
            listener.onPoints(f)
        } else {
            listener.onPoints(FloatArray(0))
        }

        prevGray?.release()
        prevGray = gray
    }

    private fun toFloats(pts: List<Point>): FloatArray {
        val f = FloatArray(pts.size * 2)
        for (i in pts.indices) {
            f[i * 2] = pts[i].x.toFloat()
            f[i * 2 + 1] = pts[i].y.toFloat()
        }
        return f
    }

    private fun toPoints(f: FloatArray): ArrayList<Point> {
        val out = ArrayList<Point>(f.size / 2)
        var i = 0
        while (i + 1 < f.size) {
            out.add(Point(f[i].toDouble(), f[i + 1].toDouble()))
            i += 2
        }
        return out
    }

    // ---------- Referenzbild (ORB) ----------

    /** Merkmale + Beschreibungen der Körperstelle, Positionen in Referenz-Koordinaten. */
    private fun buildKeyframe(gray: Mat, image: ImageProxy, quadBuffer: FloatArray) {
        val pts = quadBuffer
        var cx = 0f
        var cy = 0f
        for (i in 0 until 4) {
            cx += pts[i * 2]; cy += pts[i * 2 + 1]
        }
        cx /= 4f; cy /= 4f
        val poly = Array(4) { i ->
            Point(
                (cx + (pts[i * 2] - cx) * 2.2f).toDouble(),
                (cy + (pts[i * 2 + 1] - cy) * 2.2f).toDouble()
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
        val detector = orb ?: ORB.create(700).also { orb = it }
        val kps = MatOfKeyPoint()
        val desc = Mat()
        detector.detectAndCompute(gray, mask, kps, desc)
        mask.release()
        val arr = kps.toArray()
        kps.release()
        if (arr.size < 25 || desc.empty()) {
            desc.release()
            return
        }
        // In Referenz-Koordinaten umrechnen
        val inv = Matrix()
        if (!hCur.invert(inv)) {
            desc.release()
            return
        }
        val f = FloatArray(arr.size * 2)
        for (i in arr.indices) {
            f[i * 2] = arr[i].pt.x.toFloat()
            f[i * 2 + 1] = arr[i].pt.y.toFloat()
        }
        inv.mapPoints(f)
        keyPts = toPoints(f)
        keyDesc = desc
    }

    /** Sucht die Merkmale des Referenzbildes im aktuellen Bild und schätzt Referenz -> jetzt. */
    private fun relocalize(gray: Mat, image: ImageProxy, refQuad: FloatArray?, w: Double, h: Double): Matrix? {
        val kd = keyDesc ?: return null
        val detector = orb ?: ORB.create(700).also { orb = it }
        val mask = try {
            skinMaskAll(image)
        } catch (t: Throwable) {
            null
        }
        val kps = MatOfKeyPoint()
        val desc = Mat()
        detector.detectAndCompute(gray, mask ?: Mat(), kps, desc)
        mask?.release()
        val cur = kps.toArray()
        kps.release()
        if (cur.size < 15 || desc.empty()) {
            desc.release()
            return null
        }
        val m = matcher ?: DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING).also { matcher = it }
        val knn = ArrayList<MatOfDMatch>()
        m.knnMatch(kd, desc, knn, 2)
        desc.release()
        val src = ArrayList<Point>()
        val dst = ArrayList<Point>()
        for (mm in knn) {
            val d = mm.toArray()
            mm.release()
            if (d.isEmpty()) continue
            if (d.size >= 2 && d[0].distance > 0.8f * d[1].distance) continue
            val qi = d[0].queryIdx
            val ti = d[0].trainIdx
            if (qi < 0 || qi >= keyPts.size || ti < 0 || ti >= cur.size) continue
            src.add(keyPts[qi])
            dst.add(cur[ti].pt)
        }
        if (src.size < 15) return null
        val srcM = MatOfPoint2f(*src.toTypedArray())
        val dstM = MatOfPoint2f(*dst.toTypedArray())
        val inl = Mat()
        val aff = Calib3d.estimateAffinePartial2D(srcM, dstM, inl, Calib3d.RANSAC, 4.0)
        srcM.release(); dstM.release()
        if (aff.empty()) {
            aff.release(); inl.release()
            return null
        }
        val count = inliers(inl, src.size).count { it }
        val res = toMatrixAffine(aff)
        aff.release(); inl.release()
        // Strenger: genug und anteilig viele übereinstimmende Merkmale, sonst falscher Treffer
        if (count < 15 || count < src.size * 0.3f || !quadOk(res, refQuad, w, h)) return null
        return res
    }

    // ---------- Körpermaske ----------

    /**
     * Liefert die neueste KI-Segmentierung (oder null) und stößt bei Bedarf im Hintergrund
     * eine neue an. Blockiert das Tracking nicht.
     */
    private fun currentSeg(image: ImageProxy): BodySegmenter.Seg? {
        if (segState >= 0 && !segBusy && frameCount - segRequestFrame >= 4) {
            segRequestFrame = frameCount
            segBusy = true
            val bmp = try {
                image.toBitmap()
            } catch (t: Throwable) {
                null
            }
            val rot = image.imageInfo.rotationDegrees
            if (bmp == null) {
                segBusy = false
            } else {
                try {
                    segExecutor.execute {
                        try {
                            if (segState == 0) {
                                segmenter = try {
                                    BodySegmenter(appContext)
                                } catch (t: Throwable) {
                                    null
                                }
                                segState = if (segmenter?.available == true) 1 else -1
                            }
                            val sg = segmenter
                            if (sg != null && segState == 1) {
                                val r = sg.segmentBitmap(bmp, rot)
                                if (r != null) {
                                    segLatest = r
                                    segVersion++
                                }
                            }
                        } catch (t: Throwable) {
                        } finally {
                            bmp.recycle()
                            segBusy = false
                        }
                    }
                } catch (e: Exception) {
                    bmp.recycle()
                    segBusy = false
                }
            }
        }
        val latest = segLatest ?: return null
        if (latest.bufferW != image.width || latest.bufferH != image.height) return null
        return latest
    }

    /** true, solange das Modell nicht nachweislich fehlt (dann keine Hautfarben-Suche). */
    private fun segAvailable(): Boolean = segState >= 0

    /** Zusammenhängender Hautbereich (KI-Maske) an der Stelle (mx, my) in Masken-Koordinaten. */
    private fun componentAt(s: BodySegmenter.Seg, mx: Float, my: Float): BooleanArray? {
        val w = s.w
        val h = s.h
        val cx = mx.toInt()
        val cy = my.toInt()
        if (cx < -w / 4 || cy < -h / 4 || cx > w + w / 4 || cy > h + h / 4) return null
        // Nächstgelegenen Hautpixel suchen (Tippfehler / grobe Maske)
        var start = -1
        var best = Int.MAX_VALUE
        val r = 10
        for (y in max(0, cy - r)..min(h - 1, cy + r)) {
            for (x in max(0, cx - r)..min(w - 1, cx + r)) {
                if (!s.skin[y * w + x]) continue
                val d = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                if (d < best) {
                    best = d
                    start = y * w + x
                }
            }
        }
        if (start < 0) return null
        val comp = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        queue[tail++] = start
        comp[start] = true
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > 0 && s.skin[i - 1] && !comp[i - 1]) { comp[i - 1] = true; queue[tail++] = i - 1 }
            if (x < w - 1 && s.skin[i + 1] && !comp[i + 1]) { comp[i + 1] = true; queue[tail++] = i + 1 }
            if (y > 0 && s.skin[i - w] && !comp[i - w]) { comp[i - w] = true; queue[tail++] = i - w }
            if (y < h - 1 && s.skin[i + w] && !comp[i + w]) { comp[i + w] = true; queue[tail++] = i + w }
        }
        return if (tail < 40) null else comp
    }

    /** Masken-Array (KI-Auflösung) -> Mat in Kamerapuffer-Auflösung (255 = Haut), Rand leicht abgetragen. */
    private fun toBufferMat(mask: BooleanArray, s: BodySegmenter.Seg): Mat {
        val bytes = ByteArray(s.w * s.h) { if (mask[it]) -1 else 0 }
        val small = Mat(s.h, s.w, CvType.CV_8UC1)
        small.put(0, 0, bytes)
        val v = FloatArray(9)
        s.toBuffer.getValues(v)
        val aff = Mat(2, 3, CvType.CV_64F)
        aff.put(0, 0, v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), v[3].toDouble(), v[4].toDouble(), v[5].toDouble())
        val full = Mat()
        Imgproc.warpAffine(small, full, aff, Size(s.bufferW.toDouble(), s.bufferH.toDouble()), Imgproc.INTER_NEAREST)
        small.release(); aff.release()
        val k = max(3, (s.bufferW.toFloat() / s.w * 1.5f).toInt()) or 1
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))
        Imgproc.erode(full, full, kernel)
        kernel.release()
        return full
    }

    /** Hautbereich an der Tattoo-Stelle (Puffer-Koordinaten), für das Setzen neuer Punkte. */
    private fun skinMask(image: ImageProxy, cx: Float, cy: Float): Mat? {
        val s = currentSeg(image)
        if (s != null) {
            val p = floatArrayOf(cx, cy)
            s.fromBuffer.mapPoints(p)
            val comp = componentAt(s, p[0], p[1]) ?: return null
            return toBufferMat(comp, s)
        }
        // Modell vorhanden, aber dort keine Haut erkannt -> KEINE Hautfarben-Suche (Holzboden!)
        if (segAvailable()) return null
        return chromaMask(image, cx, cy)
    }

    /** Alle Hautbereiche im Bild (Puffer-Koordinaten), für das Wiederfinden. */
    private fun skinMaskAll(image: ImageProxy): Mat? {
        val s = currentSeg(image)
        if (s != null) {
            if (s.skin.count { it } < 50) return null
            return toBufferMat(s.skin, s)
        }
        if (segAvailable()) return null
        return chromaMaskAll(image)
    }

    /**
     * Misst die Körperstelle unter dem Tattoo: Hauptachse (Richtung von Arm/Bein) aus den
     * Momenten des Hautbereichs und die Breite quer dazu (mehrere Querschnitte gemittelt).
     */
    private fun measureLimb(s: BodySegmenter.Seg, quadB: FloatArray, b2v: Matrix): FloatArray? {
        val q = quadB.copyOf()
        s.fromBuffer.mapPoints(q)
        var cx = 0f
        var cy = 0f
        for (i in 0 until 4) {
            cx += q[i * 2]; cy += q[i * 2 + 1]
        }
        cx /= 4f; cy /= 4f
        var size = 0f
        for (i in 0 until 4) size = max(size, hypot(q[i * 2] - cx, q[i * 2 + 1] - cy))
        val comp = componentAt(s, cx, cy) ?: return null
        val w = s.w
        val h = s.h
        val r = (size * 1.8f).coerceIn(8f, max(w, h).toFloat())
        var n = 0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        val r2 = r * r
        for (y in max(0, (cy - r).toInt())..min(h - 1, (cy + r).toInt())) {
            for (x in max(0, (cx - r).toInt())..min(w - 1, (cx + r).toInt())) {
                if (!comp[y * w + x]) continue
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy > r2) continue
                n++
                sx += x; sy += y
                sxx += x.toDouble() * x; syy += y.toDouble() * y; sxy += x.toDouble() * y
            }
        }
        if (n < 40) return null
        val mx = sx / n
        val my = sy / n
        val cxx = sxx / n - mx * mx
        val cyy = syy / n - my * my
        val cxy = sxy / n - mx * my
        val theta = 0.5 * atan2(2 * cxy, cxx - cyy)
        val dx = cos(theta).toFloat()
        val dy = sin(theta).toFloat()
        val nx = -dy
        val ny = dx

        var sumMid = 0f
        var sumHalf = 0f
        var cnt = 0
        for (k in -2..2) {
            val bx = cx + dx * k * size * 0.3f
            val by = cy + dy * k * size * 0.3f
            val a = walk(comp, w, h, bx, by, nx, ny)
            val b = walk(comp, w, h, bx, by, -nx, -ny)
            if (a < 0f && b < 0f) continue
            val t1 = if (a < 0f) b else a
            val t2 = if (b < 0f) a else b
            if (t1 + t2 < 2f) continue
            sumMid += (t1 - t2) / 2f
            sumHalf += (t1 + t2) / 2f
            cnt++
        }
        if (cnt == 0) return null
        val mid = sumMid / cnt
        val half = sumHalf / cnt
        if (half < 3f) return null
        val ax = cx + nx * mid
        val ay = cy + ny * mid
        val pts = floatArrayOf(ax, ay, ax + dx * half, ay + dy * half, ax + nx * half, ay + ny * half)
        s.toBuffer.mapPoints(pts)
        b2v.mapPoints(pts)
        val vdx = pts[2] - pts[0]
        val vdy = pts[3] - pts[1]
        val ld = hypot(vdx, vdy)
        val vnx = pts[4] - pts[0]
        val vny = pts[5] - pts[1]
        val ln = hypot(vnx, vny)
        if (ld < 1e-3f || ln < 2f) return null
        return floatArrayOf(pts[0], pts[1], vdx / ld, vdy / ld, vnx / ln, vny / ln, ln)
    }

    /** Abstand bis zum Rand des Hautbereichs in Richtung (dx, dy); -1 = Bildrand erreicht (Rand unbekannt). */
    private fun walk(comp: BooleanArray, w: Int, h: Int, x0: Float, y0: Float, dx: Float, dy: Float): Float {
        var t = 0f
        val maxT = (w + h).toFloat()
        while (t < maxT) {
            val x = (x0 + dx * t).toInt()
            val y = (y0 + dy * t).toInt()
            if (x < 0 || y < 0 || x >= w || y >= h) return -1f
            if (!comp[y * w + x]) return t
            t += 0.5f
        }
        return -1f
    }

    /** Notlösung ohne KI-Modell: alle Pixel in Hautfarbe, für das Wiederfinden. */
    private fun chromaMaskAll(image: ImageProxy): Mat? {
        if (refCb < 0f) return null
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
        val bin = ByteArray(cw * ch)
        var n = 0
        for (i in 0 until cw * ch) {
            val dcb = (cbBytes[i].toInt() and 0xFF) - refCb
            val dcr = (crBytes[i].toInt() and 0xFF) - refCr
            if (dcb * dcb / (16f * 16f) + dcr * dcr / (14f * 14f) <= 1f) {
                bin[i] = -1
                n++
            }
        }
        if (n < 200) return null
        val small = Mat(ch, cw, CvType.CV_8UC1)
        small.put(0, 0, bin)
        val k5 = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.morphologyEx(small, small, Imgproc.MORPH_CLOSE, k5)
        k5.release()
        val full = Mat()
        Imgproc.resize(small, full, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
        small.release()
        return full
    }

    /**
     * Schätzt Referenz -> aktuell als Ähnlichkeitstransformation (Verschiebung, Drehung, Größe).
     * Eine volle Homographie ist bei Punkten auf einem gewölbten Arm schlecht bestimmt und erzeugt
     * bei Kamerabewegungen falsche Verzerrungen/Verdrehungen – deshalb bewusst nur 4 Freiheitsgrade.
     */
    private fun estimate(
        ref: List<Point>, cur: List<Point>, refQuad: FloatArray?, w: Double, h: Double
    ): Pair<Matrix, BooleanArray>? {
        val srcM = MatOfPoint2f(*ref.toTypedArray())
        val dstM = MatOfPoint2f(*cur.toTypedArray())
        try {
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
    private fun seed(gray: Mat, quadBuffer: FloatArray, image: ImageProxy): List<Point> {
        val pts = quadBuffer
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
     * Notlösung ohne KI-Modell: Pixel mit ähnlicher Hautfarbe (Cb/Cr) wie an der angetippten Stelle,
     * zusammenhängend mit dieser Stelle. Volle Puffer-Auflösung, 255 = Haut.
     */
    private fun chromaMask(image: ImageProxy, cx: Float, cy: Float): Mat? {
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

    /** Gibt das KI-Modell frei (auf dem Analyse-Thread aufrufen). */
    fun close() {
        try {
            segExecutor.execute {
                segmenter?.close()
                segmenter = null
            }
        } catch (e: Exception) {
        }
        segExecutor.shutdown()
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
