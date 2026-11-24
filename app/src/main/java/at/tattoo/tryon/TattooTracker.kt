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

/**
 * Verfolgt die angetippte Hautstelle im Kamerabild (Optical Flow + Homographie, OpenCV).
 * Arbeitet im Puffer-Koordinatensystem der Kamera und rechnet Bewegungen
 * mit der von CameraX gelieferten Transformation in View-Koordinaten um.
 */
class TattooTracker(private val listener: Listener) : ImageAnalysis.Analyzer {

    interface Listener {
        /** Bewegung vom vorherigen zum aktuellen Bild, in View-Koordinaten. Analyse-Thread! */
        fun onMotion(motion: Matrix)

        /** Tracking-Qualität geändert (true = stabil). Analyse-Thread! */
        fun onTrackingState(stable: Boolean)

        /** Aktuelles Tattoo-Viereck (8 Werte, View-Koordinaten) oder null. Analyse-Thread! */
        fun currentQuad(): FloatArray?
    }

    @Volatile
    var active = false
        set(value) {
            field = value
            if (value) restart()
        }

    @Volatile
    private var sensorToView: Matrix? = null

    @Volatile
    private var restartRequested = true

    @Volatile
    private var reseedRequested = true

    private var prevGray: Mat? = null
    private var prevPts: MatOfPoint2f? = null
    private var bytes = ByteArray(0)
    private var failures = 0
    private var lastStable: Boolean? = null
    private var frameCount = 0

    // Hautfarbe (Cb/Cr) an der angetippten Stelle: Merkmale werden nur auf dieser Körperstelle gesucht,
    // nicht im Hintergrund. Sonst "gewinnt" bei einer Hand-Drehung der stillstehende Hintergrund.
    private var refCb = -1f
    private var refCr = -1f
    private var cbBytes = ByteArray(0)
    private var crBytes = ByteArray(0)
    private var clahe: CLAHE? = null

    override fun getTargetCoordinateSystem(): Int = CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED

    override fun updateTransform(matrix: Matrix?) {
        sensorToView = matrix?.let { Matrix(it) }
    }

    /** Merkmale neu im aktuellen Tattoo-Bereich suchen (z. B. nach Verschieben). */
    fun requestReseed() {
        reseedRequested = true
    }

    /** Verlauf verwerfen und frisch beginnen. */
    fun restart() {
        restartRequested = true
        reseedRequested = true
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
        }
        if (!active) {
            releaseState()
            return
        }
        val s2v = sensorToView ?: return
        val s2b = image.imageInfo.sensorToBufferTransformMatrix
        val b2s = Matrix()
        if (!s2b.invert(b2s)) return
        val b2v = Matrix(b2s)
        b2v.postConcat(s2v)
        val v2b = Matrix()
        if (!b2v.invert(v2b)) return

        val gray = toGray(image)
        // Kontrast lokal anheben -> mehr verfolgbare Hautstruktur (Poren, Falten, Härchen)
        val cl = clahe ?: Imgproc.createCLAHE(2.5, Size(8.0, 8.0)).also { clahe = it }
        cl.apply(gray, gray)
        frameCount++

        var points: MatOfPoint2f? = null
        val prev = prevGray
        val prevP = prevPts
        if (prev != null && prevP != null && prevP.rows() >= 6 && prev.cols() == gray.cols() && prev.rows() == gray.rows()) {
            val next = MatOfPoint2f()
            val status = MatOfByte()
            val err = MatOfFloat()
            Video.calcOpticalFlowPyrLK(prev, gray, prevP, next, status, err, Size(21.0, 21.0), 3)
            // Rückwärts-Kontrolle: nur Punkte behalten, die zurückverfolgt wieder am Ausgangspunkt landen
            val back = MatOfPoint2f()
            val status2 = MatOfByte()
            val err2 = MatOfFloat()
            Video.calcOpticalFlowPyrLK(gray, prev, next, back, status2, err2, Size(21.0, 21.0), 3)
            val p0 = prevP.toArray()
            val p1 = next.toArray()
            val pb = back.toArray()
            val st = status.toArray()
            val st2 = status2.toArray()
            back.release(); status2.release(); err2.release()
            for (i in st.indices) {
                if (i >= st2.size || i >= pb.size || st2[i].toInt() != 1) {
                    st[i] = 0
                } else {
                    val ex = pb[i].x - p0[i].x
                    val ey = pb[i].y - p0[i].y
                    if (ex * ex + ey * ey > 1.0) st[i] = 0
                }
            }
            val src = ArrayList<Point>()
            val dst = ArrayList<Point>()
            val w = gray.cols().toDouble()
            val h = gray.rows().toDouble()
            for (i in st.indices) {
                if (st[i].toInt() == 1 && i < p1.size) {
                    val q = p1[i]
                    if (q.x >= 0 && q.y >= 0 && q.x < w && q.y < h) {
                        src.add(p0[i])
                        dst.add(q)
                    }
                }
            }
            next.release(); status.release(); err.release()

            var hb: Matrix? = null
            val kept = ArrayList<Point>()
            if (src.size >= 8) {
                val srcM = MatOfPoint2f(*src.toTypedArray())
                val dstM = MatOfPoint2f(*dst.toTypedArray())
                val mask = Mat()
                val hom = Calib3d.findHomography(srcM, dstM, Calib3d.RANSAC, 2.5, mask)
                if (!hom.empty() && plausible(hom, w, h)) {
                    hb = toMatrix3(hom)
                    val m = ByteArray(mask.rows() * mask.cols())
                    if (m.isNotEmpty()) mask.get(0, 0, m)
                    for (i in dst.indices) if (i >= m.size || m[i].toInt() != 0) kept.add(dst[i])
                } else {
                    // Rückfall: Ähnlichkeitstransformation (Verschiebung, Drehung, Skalierung)
                    val inl = Mat()
                    val aff = Calib3d.estimateAffinePartial2D(srcM, dstM, inl, Calib3d.RANSAC, 3.0)
                    if (!aff.empty()) {
                        hb = toMatrixAffine(aff)
                        val m = ByteArray(inl.rows() * inl.cols())
                        if (m.isNotEmpty()) inl.get(0, 0, m)
                        for (i in dst.indices) if (i >= m.size || m[i].toInt() != 0) kept.add(dst[i])
                    }
                    aff.release(); inl.release()
                }
                hom.release(); mask.release(); srcM.release(); dstM.release()
            }

            if (hb != null) {
                val hv = Matrix(v2b)
                hv.postConcat(hb)
                hv.postConcat(b2v)
                listener.onMotion(hv)
                failures = 0
                if (kept.size >= 6) points = MatOfPoint2f(*kept.toTypedArray())
            } else {
                failures++
            }
        }

        // Neue Merkmale im Tattoo-Bereich suchen
        if (reseedRequested || points == null || points.rows() < 40 || frameCount % 20 == 0) {
            val seeded = seed(gray, v2b, image)
            if (seeded != null && seeded.rows() >= 6) {
                points?.release()
                points = seeded
                reseedRequested = false
            }
        }

        val stable = failures < 8 && points != null && points.rows() >= 8
        if (stable != lastStable) {
            lastStable = stable
            listener.onTrackingState(stable)
        }

        prevGray?.release()
        prevGray = gray
        prevPts?.release()
        prevPts = points
    }

    /** Sucht gut verfolgbare Punkte im (leicht vergrößerten) Tattoo-Viereck. */
    private fun seed(gray: Mat, v2b: Matrix, image: ImageProxy): MatOfPoint2f? {
        val quad = listener.currentQuad() ?: return null
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

        // Nur auf der Körperstelle (Hautfarbe wie an der angetippten Stelle) suchen
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

        val corners = MatOfPoint()
        Imgproc.goodFeaturesToTrack(gray, corners, 250, 0.002, 5.0, mask, 5, false, 0.04)
        mask.release()
        if (corners.empty()) {
            corners.release()
            return null
        }
        val arr = corners.toArray()
        corners.release()
        return MatOfPoint2f(*Array(arr.size) { Point(arr[it].x, arr[it].y) })
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

    /** Verwirft unrealistische Sprünge zwischen zwei Kamerabildern. */
    private fun plausible(hom: Mat, w: Double, h: Double): Boolean {
        val h00 = hom.get(0, 0)[0]
        val h01 = hom.get(0, 1)[0]
        val h10 = hom.get(1, 0)[0]
        val h11 = hom.get(1, 1)[0]
        val h20 = hom.get(2, 0)[0]
        val h21 = hom.get(2, 1)[0]
        val h22 = hom.get(2, 2)[0]
        if (abs(h22) < 1e-9) return false
        val det = (h00 * h11 - h01 * h10) / (h22 * h22)
        if (det < 0.6 || det > 1.6) return false
        if (abs(h20 / h22) * w > 0.35 || abs(h21 / h22) * h > 0.35) return false
        return true
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
        prevPts?.release()
        prevPts = null
        lastStable = null
    }
}
