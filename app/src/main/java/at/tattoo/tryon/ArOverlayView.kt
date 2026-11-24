package at.tattoo.tryon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Körperstellen, an denen das Tattoo verankert werden kann. */
enum class BodyAnchor(
    val label: String,
    val a: Int,
    val b: Int,
    val torso: Boolean,
    val widthFactor: Float,
    val torsoPosition: Float = 0f
) {
    LEFT_FOREARM("Linker Unterarm", PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST, false, 0.42f),
    RIGHT_FOREARM("Rechter Unterarm", PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST, false, 0.42f),
    LEFT_UPPER_ARM("Linker Oberarm", PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW, false, 0.42f),
    RIGHT_UPPER_ARM("Rechter Oberarm", PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, false, 0.42f),
    LEFT_THIGH("Linker Oberschenkel", PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE, false, 0.4f),
    RIGHT_THIGH("Rechter Oberschenkel", PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE, false, 0.4f),
    LEFT_CALF("Linker Unterschenkel", PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE, false, 0.36f),
    RIGHT_CALF("Rechter Unterschenkel", PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE, false, 0.36f),
    CHEST("Brust / oberer Rücken", PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER, true, 0.55f, 0.28f),
    BELLY("Bauch / unterer Rücken", PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER, true, 0.5f, 0.72f),
    FREE("Frei platzieren (ohne Tracking)", -1, -1, false, 0f);

    val tracked: Boolean get() = a >= 0
}

/**
 * Zeichnet das Tattoo über das Kamerabild und verankert es an den erkannten Körperpunkten.
 * Gesten: 1 Finger = verschieben, 2 Finger = skalieren/drehen (relativ zum Körperteil).
 */
class ArOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var tattoo: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }
    var anchor: BodyAnchor = BodyAnchor.LEFT_FOREARM
        set(value) {
            field = value
            resetAdjustments()
            smoothed.clear()
            lastSeen = 0L
            invalidate()
        }
    var opacity = 0.85f
        set(value) {
            field = value
            invalidate()
        }
    var showSkeleton = false
        set(value) {
            field = value
            invalidate()
        }
    /** Wird aufgerufen, wenn sich der Tracking-Status ändert (true = Körperteil erkannt). */
    var onTrackingChanged: ((Boolean) -> Unit)? = null

    // Benutzer-Anpassungen relativ zum Körperteil
    private var userScale = 1f
    private var userRot = 0f
    private var offX = 0f
    private var offY = 0f

    // Freier Modus (Bildschirmkoordinaten)
    private var freeX = -1f
    private var freeY = -1f
    private var freeWidth = -1f

    private val smoothed = HashMap<Int, PointF>()
    private var lastSeen = 0L
    private var lastTracked = false

    // Zuletzt berechnete Platzierung
    private var placed = false
    private var pCx = 0f
    private var pCy = 0f
    private var pBaseAngle = 0f // Grad, ohne Benutzerdrehung
    private var pRef = 1f

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC4FC3F7.toInt()
        strokeWidth = 3f * density
        style = Paint.Style.STROKE
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }

    fun resetAdjustments() {
        userScale = 1f
        userRot = 0f
        offX = 0f
        offY = 0f
        freeX = -1f
        freeY = -1f
        freeWidth = -1f
        invalidate()
    }

    private fun neededLandmarks(): IntArray = if (anchor.torso) {
        intArrayOf(
            PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP
        )
    } else if (anchor.tracked) {
        intArrayOf(anchor.a, anchor.b)
    } else {
        IntArray(0)
    }

    /** Neue Körperpose (Koordinaten bereits in View-Koordinaten dank MlKitAnalyzer). */
    fun updatePose(pose: Pose?) {
        if (!anchor.tracked) return
        val now = SystemClock.uptimeMillis()
        val required = if (anchor.torso) intArrayOf(PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER)
        else intArrayOf(anchor.a, anchor.b)

        var ok = pose != null
        if (pose != null) {
            for (t in required) {
                val lm = pose.getPoseLandmark(t)
                if (lm == null || lm.inFrameLikelihood < 0.5f) {
                    ok = false
                    break
                }
            }
        }
        if (ok && pose != null) {
            for (t in neededLandmarks()) {
                val lm = pose.getPoseLandmark(t) ?: continue
                if (lm.inFrameLikelihood < 0.5f) {
                    smoothed.remove(t)
                    continue
                }
                val p = lm.position
                val old = smoothed[t]
                if (old == null) {
                    smoothed[t] = PointF(p.x, p.y)
                } else {
                    val jump = hypot(p.x - old.x, p.y - old.y)
                    val k = if (jump > 80f * density) 1f else 0.55f
                    old.x += (p.x - old.x) * k
                    old.y += (p.y - old.y) * k
                }
            }
            lastSeen = now
        }
        val trackedNow = now - lastSeen < 700L && lastSeen != 0L
        if (trackedNow != lastTracked) {
            lastTracked = trackedNow
            onTrackingChanged?.invoke(trackedNow)
        }
        invalidate()
    }

    /** Berechnet Mittelpunkt, Basiswinkel und Referenzlänge aus den Landmarken. */
    private fun computePlacement(): Boolean {
        if (!anchor.tracked) {
            if (freeX < 0f) {
                freeX = width / 2f
                freeY = height / 2f
                freeWidth = width * 0.45f
            }
            pCx = freeX
            pCy = freeY
            pBaseAngle = 0f
            pRef = freeWidth
            return true
        }
        if (!lastTracked) return false

        if (anchor.torso) {
            val ls = smoothed[PoseLandmark.LEFT_SHOULDER] ?: return false
            val rs = smoothed[PoseLandmark.RIGHT_SHOULDER] ?: return false
            val lh = smoothed[PoseLandmark.LEFT_HIP]
            val rh = smoothed[PoseLandmark.RIGHT_HIP]
            val smx = (ls.x + rs.x) / 2f
            val smy = (ls.y + rs.y) / 2f
            val vx = ls.x - rs.x
            val vy = ls.y - rs.y
            val shoulderW = hypot(vx, vy)
            if (shoulderW < 4f) return false
            var angle = Math.toDegrees(atan2(vy.toDouble(), vx.toDouble())).toFloat()
            // Richtung "Kopf" bestimmen, damit das Tattoo nicht auf dem Kopf steht
            val headX: Float
            val headY: Float
            if (lh != null && rh != null) {
                headX = smx - (lh.x + rh.x) / 2f
                headY = smy - (lh.y + rh.y) / 2f
            } else {
                headX = 0f
                headY = -1f
            }
            val rad = Math.toRadians(angle.toDouble())
            val upX = sin(rad).toFloat()
            val upY = -cos(rad).toFloat()
            if (upX * headX + upY * headY < 0f) angle += 180f

            val torsoLen = if (lh != null && rh != null) hypot(headX, headY) else shoulderW * 1.3f
            val r2 = Math.toRadians(angle.toDouble())
            // "nach unten" im Tattoo-System = (-sin, cos)
            val downX = -sin(r2).toFloat()
            val downY = cos(r2).toFloat()
            pCx = smx + downX * torsoLen * anchor.torsoPosition
            pCy = smy + downY * torsoLen * anchor.torsoPosition
            pBaseAngle = angle
            pRef = shoulderW
            return true
        }

        val pa = smoothed[anchor.a] ?: return false
        val pb = smoothed[anchor.b] ?: return false
        val dx = pb.x - pa.x
        val dy = pb.y - pa.y
        val len = hypot(dx, dy)
        if (len < 4f) return false
        // Tattoo-y-Achse zeigt entlang des Glieds (Ellbogen -> Handgelenk)
        pBaseAngle = Math.toDegrees(atan2(-dx.toDouble(), dy.toDouble())).toFloat()
        pCx = (pa.x + pb.x) / 2f
        pCy = (pa.y + pb.y) / 2f
        pRef = len
        return true
    }

    /** Zeichnet das Tattoo auf einen beliebigen Canvas (auch für Schnappschüsse). */
    fun drawTattooOn(canvas: Canvas, multiply: Boolean): Boolean {
        val t = tattoo ?: return false
        if (!computePlacement()) return false
        val rad = Math.toRadians(pBaseAngle.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val ox = (offX * c - offY * s) * pRef
        val oy = (offX * s + offY * c) * pRef
        val widthPx = if (anchor.tracked) pRef * anchor.widthFactor * userScale else pRef * userScale
        val scale = widthPx / t.width
        paint.alpha = (opacity * 255).toInt().coerceIn(0, 255)
        paint.blendMode = if (multiply) BlendMode.MULTIPLY else null
        canvas.save()
        canvas.translate(pCx + ox, pCy + oy)
        canvas.rotate(pBaseAngle + userRot)
        canvas.scale(scale, scale)
        canvas.drawBitmap(t, -t.width / 2f, -t.height / 2f, paint)
        canvas.restore()
        placed = true
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (showSkeleton && lastTracked) drawSkeleton(canvas)
        placed = false
        drawTattooOn(canvas, multiply = false)
    }

    private fun drawSkeleton(canvas: Canvas) {
        val ids = neededLandmarks()
        val pts = ArrayList<PointF>()
        for (id in ids) smoothed[id]?.let { pts.add(it) }
        if (anchor.torso && pts.size == 4) {
            canvas.drawLine(pts[0].x, pts[0].y, pts[1].x, pts[1].y, bonePaint)
            canvas.drawLine(pts[2].x, pts[2].y, pts[3].x, pts[3].y, bonePaint)
            canvas.drawLine(pts[0].x, pts[0].y, pts[2].x, pts[2].y, bonePaint)
            canvas.drawLine(pts[1].x, pts[1].y, pts[3].x, pts[3].y, bonePaint)
        } else if (pts.size >= 2) {
            canvas.drawLine(pts[0].x, pts[0].y, pts[1].x, pts[1].y, bonePaint)
        }
        for (p in pts) canvas.drawCircle(p.x, p.y, 6f * density, jointPaint)
    }

    // ---------- Gesten ----------

    private var dragging = false
    private var multi = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastDist = 0f
    private var lastAngle = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (tattoo == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y
                dragging = true
                multi = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                lastDist = twoDist(e)
                lastAngle = twoAngle(e)
                multi = true
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi && e.pointerCount >= 2) {
                    val d = twoDist(e)
                    val a = twoAngle(e)
                    if (lastDist > 0f && d > 0f) {
                        val f = d / lastDist
                        if (anchor.tracked) userScale = (userScale * f).coerceIn(0.1f, 10f)
                        else freeWidth = (freeWidth * f).coerceIn(20f, width * 4f)
                    }
                    userRot += a - lastAngle
                    lastDist = d
                    lastAngle = a
                    invalidate()
                } else if (dragging) {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    if (anchor.tracked) {
                        if (pRef > 1f) {
                            // Bildschirmbewegung in das lokale System des Körperteils drehen
                            val rad = Math.toRadians(pBaseAngle.toDouble())
                            val c = cos(rad).toFloat()
                            val s = sin(rad).toFloat()
                            offX += (dx * c + dy * s) / pRef
                            offY += (-dx * s + dy * c) / pRef
                        }
                    } else {
                        freeX += dx
                        freeY += dy
                    }
                    lastX = e.x; lastY = e.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.pointerCount - 1 < 2) {
                multi = false
                val remaining = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(remaining); lastY = e.getY(remaining)
                dragging = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                multi = false
            }
        }
        return true
    }

    private fun twoDist(e: MotionEvent) = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
    private fun twoAngle(e: MotionEvent) =
        Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
}
