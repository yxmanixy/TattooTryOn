package at.tattoo.tryon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Zeigt das Körperfoto mit dem Tattoo darüber.
 * Ein Finger: verschieben, zwei Finger: skalieren + drehen.
 * Die Tattoo-Position wird in Foto-Koordinaten gespeichert -> Export in voller Auflösung.
 */
class TryOnView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var body: Bitmap? = null
        private set
    var tattoo: Bitmap? = null
        private set

    /** Tattoo-Bitmap -> Foto-Koordinaten */
    private val tattooMatrix = Matrix()
    /** Foto -> Bildschirm */
    private val viewMatrix = Matrix()
    private val inverse = Matrix()
    private var needsFit = true

    var opacity = 0.9f
        set(value) {
            field = value
            invalidate()
        }
    var multiply = true
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val tattooPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val bodyPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        textSize = 16f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }

    fun setBody(b: Bitmap) {
        body = b
        needsFit = true
        resetTattoo()
        invalidate()
    }

    fun setTattoo(t: Bitmap) {
        tattoo = t
        resetTattoo()
        invalidate()
    }

    /** Tattoo mittig mit ca. 35 % der Fotobreite platzieren. */
    fun resetTattoo() {
        val b = body ?: return
        val t = tattoo ?: return
        val targetW = min(b.width, b.height) * 0.4f
        val s = targetW / t.width
        tattooMatrix.setScale(s, s)
        tattooMatrix.postTranslate(b.width / 2f - t.width * s / 2f, b.height / 2f - t.height * s / 2f)
        invalidate()
    }

    fun flipTattoo() {
        val t = tattoo ?: return
        tattooMatrix.preScale(-1f, 1f, t.width / 2f, t.height / 2f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        needsFit = true
    }

    private fun fitIfNeeded() {
        val b = body ?: return
        if (!needsFit || width == 0 || height == 0) return
        val s = min(width.toFloat() / b.width, height.toFloat() / b.height)
        viewMatrix.setScale(s, s)
        viewMatrix.postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f)
        viewMatrix.invert(inverse)
        needsFit = false
    }

    private fun drawTattoo(canvas: Canvas) {
        val t = tattoo ?: return
        tattooPaint.alpha = (opacity * 255).toInt().coerceIn(0, 255)
        tattooPaint.blendMode = if (multiply) BlendMode.MULTIPLY else null
        canvas.drawBitmap(t, tattooMatrix, tattooPaint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = body
        if (b == null) {
            canvas.drawText("Nimm unten ein Körperfoto auf", width / 2f, height / 2f, hintPaint)
            canvas.drawText("oder wähle eines aus der Galerie.", width / 2f, height / 2f + 24f * density, hintPaint)
            return
        }
        fitIfNeeded()
        canvas.save()
        canvas.concat(viewMatrix)
        canvas.drawBitmap(b, 0f, 0f, bodyPaint)
        drawTattoo(canvas)
        canvas.restore()
    }

    /** Erzeugt das fertige Bild in Originalauflösung des Körperfotos. */
    fun renderComposite(): Bitmap? {
        val b = body ?: return null
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        c.drawBitmap(b, 0f, 0f, bodyPaint)
        drawTattoo(c)
        return out
    }

    // ---------- Gesten ----------

    private var dragging = false
    private var multi = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastDist = 0f
    private var lastAngle = 0f
    private var lastMidX = 0f
    private var lastMidY = 0f

    /** Wandelt Bildschirm- in Foto-Koordinaten um. */
    private fun toBody(x: Float, y: Float): FloatArray {
        val p = floatArrayOf(x, y)
        inverse.mapPoints(p)
        return p
    }

    private fun readTwo(e: MotionEvent): FloatArray {
        val a = toBody(e.getX(0), e.getY(0))
        val b = toBody(e.getX(1), e.getY(1))
        val dist = hypot(b[0] - a[0], b[1] - a[1])
        val angle = Math.toDegrees(atan2((b[1] - a[1]).toDouble(), (b[0] - a[0]).toDouble())).toFloat()
        return floatArrayOf((a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f, dist, angle)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (body == null || tattoo == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val p = toBody(e.x, e.y)
                lastX = p[0]; lastY = p[1]
                dragging = true
                multi = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {
                    val r = readTwo(e)
                    lastMidX = r[0]; lastMidY = r[1]; lastDist = r[2]; lastAngle = r[3]
                    multi = true
                    dragging = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi && e.pointerCount >= 2) {
                    val r = readTwo(e)
                    if (lastDist > 0f && r[2] > 0f) {
                        val s = r[2] / lastDist
                        tattooMatrix.postScale(s, s, lastMidX, lastMidY)
                    }
                    tattooMatrix.postRotate(r[3] - lastAngle, lastMidX, lastMidY)
                    tattooMatrix.postTranslate(r[0] - lastMidX, r[1] - lastMidY)
                    lastMidX = r[0]; lastMidY = r[1]; lastDist = r[2]; lastAngle = r[3]
                    invalidate()
                } else if (dragging) {
                    val p = toBody(e.x, e.y)
                    tattooMatrix.postTranslate(p[0] - lastX, p[1] - lastY)
                    lastX = p[0]; lastY = p[1]
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.pointerCount - 1 < 2) {
                    multi = false
                    // Mit dem verbleibenden Finger weiterziehen
                    val remaining = if (e.actionIndex == 0) 1 else 0
                    val p = toBody(e.getX(remaining), e.getY(remaining))
                    lastX = p[0]; lastY = p[1]
                    dragging = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                multi = false
            }
        }
        return true
    }
}
