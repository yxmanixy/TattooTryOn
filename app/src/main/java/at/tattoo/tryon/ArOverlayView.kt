package at.tattoo.tryon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Zeichnet das Tattoo als perspektivisch verzerrbares Viereck über das Kamerabild.
 * - schwebt nach dem Öffnen in der Mitte
 * - Antippen = an dieser Stelle verankern (danach bewegt es der Tracker mit)
 * - 1 Finger = verschieben, 2 Finger = skalieren/drehen
 * - Standbild: eingefrorenes Kamerabild + Tattoo mit Hauteffekt
 */
class ArOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onAnchored()
        fun onQuadEdited()
    }

    var listener: Listener? = null

    private var tattoo: Bitmap? = null

    /** Ecken TL, TR, BR, BL in View-Koordinaten. */
    private val quad = FloatArray(8)
    private var quadValid = false

    @Volatile
    private var quadSnapshot: FloatArray? = null

    var anchored = false
        private set

    var frozenFrame: Bitmap? = null
        private set

    var opacity = 0.9f
        set(value) {
            field = value
            invalidate()
        }


    /** Zum Prüfen: verfolgte Punkte anzeigen. */
    var showPoints = false
        set(value) {
            field = value
            invalidate()
        }
    private var trackedPoints: FloatArray? = null
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF39FF88.toInt() }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD54F.toInt()
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFD54F.toInt()
        strokeWidth = 1.5f * resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    fun setTrackedPoints(p: FloatArray?) {
        trackedPoints = p
        if (showPoints) invalidate()
    }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val main = Handler(Looper.getMainLooper())
    private var meshVerts = FloatArray(0)
    private var meshColors = IntArray(0)

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val drawMatrix = Matrix()
    private val outlinePath = Path()
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.WHITE
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 8f * density), 0f)
    }
    private val outlineShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        color = 0x66000000
    }

    // ---------- Tattoo / Wölbung ----------

    fun setTattoo(bmp: Bitmap) {
        // Für die Live-Ansicht reicht eine moderate Auflösung
        val maxSide = 1200
        val longest = max(bmp.width, bmp.height)
        tattoo = if (longest > maxSide) {
            val s = maxSide.toFloat() / longest
            Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
        } else {
            bmp
        }
        quadValid = false
        anchored = false
        invalidate()
    }

    /** Stärke der automatischen Körperform-Anpassung (0 = flach). */
    var shapeStrength = 1f
        set(value) {
            field = value
            invalidate()
        }

    /** Geglättete Körperform [ax, ay, dx, dy, nx, ny, halbe Breite] in View-Koordinaten. */
    private var limb: FloatArray? = null
    private var limbMisses = 0

    val hasLimb: Boolean get() = limb != null

    /** Neue Messung der Körperform vom Tracker (UI-Thread). */
    fun setLimb(l: FloatArray?) {
        if (l == null) {
            limbMisses++
            if (limbMisses > 6) limb = null
            invalidate()
            return
        }
        limbMisses = 0
        val cur = limb
        if (cur == null) {
            limb = l.copyOf()
        } else {
            // Richtung ggf. umdrehen, damit nicht zwischen +/- gesprungen wird
            if (cur[2] * l[2] + cur[3] * l[3] < 0f) {
                l[2] = -l[2]; l[3] = -l[3]
            }
            if (cur[4] * l[4] + cur[5] * l[5] < 0f) {
                l[4] = -l[4]; l[5] = -l[5]
            }
            for (i in 0 until 7) cur[i] += (l[i] - cur[i]) * 0.35f
            val ld = hypot(cur[2], cur[3]).coerceAtLeast(1e-4f)
            cur[2] /= ld; cur[3] /= ld
            val ln = hypot(cur[4], cur[5]).coerceAtLeast(1e-4f)
            cur[4] /= ln; cur[5] /= ln
        }
        invalidate()
    }

    fun clearLimb() {
        limb = null
        limbMisses = 0
        invalidate()
    }

    // ---------- Viereck ----------

    private fun ensureQuad() {
        if (quadValid || width == 0 || height == 0) return
        val t = tattoo ?: return
        resetQuad(width / 2f, height / 2f, t)
    }

    private fun resetQuad(cx: Float, cy: Float, t: Bitmap) {
        val qw = width * 0.45f
        val qh = qw * t.height / t.width
        quad[0] = cx - qw / 2; quad[1] = cy - qh / 2
        quad[2] = cx + qw / 2; quad[3] = cy - qh / 2
        quad[4] = cx + qw / 2; quad[5] = cy + qh / 2
        quad[6] = cx - qw / 2; quad[7] = cy + qh / 2
        quadValid = true
        publish()
    }

    private fun publish() {
        quadSnapshot = quad.copyOf()
    }

    /** Für den Tracker (Analyse-Thread). */
    fun quadForTracking(): FloatArray? = quadSnapshot

    private fun center(): FloatArray {
        var x = 0f
        var y = 0f
        for (i in 0 until 4) {
            x += quad[i * 2]; y += quad[i * 2 + 1]
        }
        return floatArrayOf(x / 4f, y / 4f)
    }

    // Referenzlage (Moment des Verankerns) und letzte Abbildung Referenz -> jetzt
    private val refQuad = FloatArray(8)
    private val lastT = Matrix()

    @Volatile
    private var refSnapshot: FloatArray? = null

    /** Für den Tracker (Analyse-Thread). */
    fun referenceQuadForTracking(): FloatArray? = refSnapshot

    /** Aktuelle Lage wird zur neuen Ausgangslage (beim Verankern / nach dem Standbild). */
    fun resetReference() {
        System.arraycopy(quad, 0, refQuad, 0, 8)
        lastT.reset()
        refSnapshot = refQuad.copyOf()
    }

    /**
     * Setzt das Tattoo auf: Referenz-Viereck abgebildet mit der aktuellen Transformation.
     * Weil immer von der Referenz aus gerechnet wird, sammeln sich keine Verzerrungen an.
     */
    fun applyTransform(t: Matrix) {
        if (!anchored || frozenFrame != null || !quadValid) return
        val next = refQuad.copyOf()
        t.mapPoints(next)
        val a0 = area(refQuad)
        val a1 = area(next)
        if (!convex(next) || a1 < a0 * 0.15f || a1 > a0 * 6f) return
        var cx = 0f
        var cy = 0f
        for (i in 0 until 4) {
            cx += next[i * 2]; cy += next[i * 2 + 1]
        }
        cx /= 4f; cy /= 4f
        if (cx < -width || cx > 2f * width || cy < -height || cy > 2f * height) return
        // Leichte Glättung gegen Zittern
        // Kaum Glättung, damit das Tattoo ohne Verzögerung folgt
        for (i in 0 until 8) quad[i] += (next[i] - quad[i]) * 0.92f
        lastT.set(t)
        publish()
        invalidate()
    }

    /** Löst das Tattoo und setzt es zurück in die Bildmitte. */
    fun release() {
        anchored = false
        val t = tattoo
        if (t != null && width > 0) resetQuad(width / 2f, height / 2f, t) else quadValid = false
        invalidate()
    }

    fun freeze(frame: Bitmap) {
        frozenFrame = frame
        invalidate()
    }

    fun unfreeze() {
        frozenFrame = null
        invalidate()
    }

    // ---------- Zeichnen ----------

    /**
     * Zeichnet das Tattoo auf einen Canvas (auch für das Speichern).
     * Das perspektivische Viereck wird zusätzlich um die erkannte Körperform (Zylinder entlang
     * der Arm-/Beinachse) gelegt: zur Körperkante hin gestaucht und ausgeblendet.
     */
    fun drawTattoo(canvas: Canvas, multiply: Boolean) {
        val bmp = tattoo ?: return
        ensureQuad()
        if (!quadValid) return
        val src = floatArrayOf(
            0f, 0f,
            bmp.width.toFloat(), 0f,
            bmp.width.toFloat(), bmp.height.toFloat(),
            0f, bmp.height.toFloat()
        )
        if (!drawMatrix.setPolyToPoly(src, 0, quad, 0, 4)) return
        paint.alpha = (opacity * 255).toInt().coerceIn(0, 255)
        paint.blendMode = if (multiply) BlendMode.MULTIPLY else null

        val k = shapeStrength
        val shape = shapeFrame()
        if (k <= 0.01f || shape == null) {
            canvas.drawBitmap(bmp, drawMatrix, paint)
            return
        }
        val ax = shape[0]
        val ay = shape[1]
        val nx = shape[4]
        val ny = shape[5]
        val half = shape[6]

        val m = MESH
        val count = (m + 1) * (m + 1)
        if (meshVerts.size != count * 2) {
            meshVerts = FloatArray(count * 2)
            meshColors = IntArray(count)
        }
        var idx = 0
        for (j in 0..m) {
            for (i in 0..m) {
                meshVerts[idx * 2] = bmp.width * i / m.toFloat()
                meshVerts[idx * 2 + 1] = bmp.height * j / m.toFloat()
                idx++
            }
        }
        drawMatrix.mapPoints(meshVerts)
        val maxPhi = 1.45f
        val shiftK = min(k, 1f)
        val curveK = min(1.5f, max(1f, k))
        for (v in 0 until count) {
            val px = meshVerts[v * 2]
            val py = meshVerts[v * 2 + 1]
            // Abstand quer zur Achse = Bogenlänge auf dem Zylinder
            val sDist = (px - ax) * nx + (py - ay) * ny
            var phi = sDist / half * curveK
            val outside = abs(phi) > maxPhi
            phi = phi.coerceIn(-maxPhi, maxPhi)
            val projected = half * sin(phi) / curveK
            val shift = (projected - sDist) * shiftK
            meshVerts[v * 2] = px + nx * shift
            meshVerts[v * 2 + 1] = py + ny * shift
            val fade = if (outside) 0f else 1f - shiftK * (1f - (0.3f + 0.7f * cos(phi)))
            meshColors[v] = Color.argb((fade * 255f).toInt().coerceIn(0, 255), 255, 255, 255)
        }
        canvas.drawBitmapMesh(bmp, m, m, meshVerts, 0, meshColors, 0, paint)
    }

    /**
     * Körperform für das Zeichnen: erkannte Arm-/Beinachse, sonst eine Annahme aus dem Tattoo selbst
     * (Achse entlang der Tattoo-Höhe, Breite etwas größer als das Tattoo).
     */
    private fun shapeFrame(): FloatArray? {
        limb?.let { return it }
        if (!anchored) return null
        val topX = (quad[0] + quad[2]) / 2f
        val topY = (quad[1] + quad[3]) / 2f
        val botX = (quad[4] + quad[6]) / 2f
        val botY = (quad[5] + quad[7]) / 2f
        val leftX = (quad[0] + quad[6]) / 2f
        val leftY = (quad[1] + quad[7]) / 2f
        val rightX = (quad[2] + quad[4]) / 2f
        val rightY = (quad[3] + quad[5]) / 2f
        val dl = hypot(botX - topX, botY - topY)
        val wl = hypot(rightX - leftX, rightY - leftY)
        if (dl < 1f || wl < 1f) return null
        val dx = (botX - topX) / dl
        val dy = (botY - topY) / dl
        val nx = (rightX - leftX) / wl
        val ny = (rightY - leftY) / wl
        return floatArrayOf((leftX + rightX) / 2f, (leftY + rightY) / 2f, dx, dy, nx, ny, wl * 0.8f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = frozenFrame
        if (frame != null) {
            canvas.drawBitmap(frame, null, android.graphics.Rect(0, 0, width, height), framePaint)
            drawTattoo(canvas, multiply = true)
        } else {
            drawTattoo(canvas, multiply = false)
        }
        if (showPoints && frame == null) {
            limb?.let { l ->
                val len = max(width, height).toFloat()
                for (side in -1..1) {
                    val ox = l[0] + l[4] * l[6] * side
                    val oy = l[1] + l[5] * l[6] * side
                    canvas.drawLine(
                        ox - l[2] * len, oy - l[3] * len, ox + l[2] * len, oy + l[3] * len,
                        if (side == 0) axisPaint else edgePaint
                    )
                }
            }
            trackedPoints?.let { p ->
                var i = 0
                while (i + 1 < p.size) {
                    canvas.drawCircle(p[i], p[i + 1], 3f * density, pointPaint)
                    i += 2
                }
            }
        }
        if (!anchored && quadValid && frame == null) {
            outlinePath.reset()
            outlinePath.moveTo(quad[0], quad[1])
            outlinePath.lineTo(quad[2], quad[3])
            outlinePath.lineTo(quad[4], quad[5])
            outlinePath.lineTo(quad[6], quad[7])
            outlinePath.close()
            canvas.drawPath(outlinePath, outlineShadow)
            canvas.drawPath(outlinePath, outlinePaint)
        }
    }

    // ---------- Gesten ----------

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var multi = false
    private var hadMulti = false
    private var lastDist = 0f
    private var lastAngle = 0f
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var edited = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (tattoo == null) return false
        ensureQuad()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                lastX = e.x; lastY = e.y
                downTime = SystemClock.uptimeMillis()
                moved = false
                multi = false
                hadMulti = false
                edited = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                multi = true
                hadMulti = true
                lastDist = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
                lastAngle = angle(e)
                lastMidX = (e.getX(0) + e.getX(1)) / 2f
                lastMidY = (e.getY(0) + e.getY(1)) / 2f
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi && e.pointerCount >= 2) {
                    val d = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
                    val a = angle(e)
                    val mx = (e.getX(0) + e.getX(1)) / 2f
                    val my = (e.getY(0) + e.getY(1)) / 2f
                    val m = Matrix()
                    if (lastDist > 0f && d > 0f) {
                        val s = d / lastDist
                        m.postScale(s, s, lastMidX, lastMidY)
                    }
                    m.postRotate(a - lastAngle, lastMidX, lastMidY)
                    m.postTranslate(mx - lastMidX, my - lastMidY)
                    transformQuad(m)
                    lastDist = d; lastAngle = a; lastMidX = mx; lastMidY = my
                } else if (!multi) {
                    if (!moved && hypot(e.x - downX, e.y - downY) > touchSlop) moved = true
                    if (moved) {
                        val m = Matrix()
                        m.setTranslate(e.x - lastX, e.y - lastY)
                        transformQuad(m)
                    }
                    lastX = e.x; lastY = e.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.pointerCount - 1 < 2) {
                multi = false
                val remaining = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(remaining); lastY = e.getY(remaining)
                moved = true
            }
            MotionEvent.ACTION_UP -> {
                val isTap = !moved && !hadMulti && SystemClock.uptimeMillis() - downTime < 400
                if (isTap && frozenFrame == null) {
                    // Tattoo an die angetippte Stelle setzen und verankern
                    val c = center()
                    val m = Matrix()
                    m.setTranslate(e.x - c[0], e.y - c[1])
                    m.mapPoints(quad)
                    publish()
                    anchored = true
                    resetReference()
                    invalidate()
                    listener?.onAnchored()
                } else if (edited && anchored) {
                    listener?.onQuadEdited()
                }
                multi = false
            }
            MotionEvent.ACTION_CANCEL -> {
                multi = false
                if (edited && anchored) listener?.onQuadEdited()
            }
        }
        return true
    }

    private fun transformQuad(m: Matrix) {
        m.mapPoints(quad)
        if (anchored) {
            // Änderung in die Referenzlage zurückrechnen, damit sie beim Tracking erhalten bleibt
            val inv = Matrix()
            if (lastT.invert(inv)) {
                val r = quad.copyOf()
                inv.mapPoints(r)
                System.arraycopy(r, 0, refQuad, 0, 8)
                refSnapshot = refQuad.copyOf()
            }
        }
        publish()
        edited = true
        invalidate()
    }

    private fun angle(e: MotionEvent): Float =
        Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }

    companion object {
        private fun area(q: FloatArray): Float {
            var a = 0f
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                a += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1]
            }
            return abs(a) / 2f
        }

        private fun convex(q: FloatArray): Boolean {
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

        private const val MESH = 22
    }
}
