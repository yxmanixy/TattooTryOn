package at.tattoo.tryon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Anzeige + Touch-Eingabe für das Freistellen. Zwei Finger = verschieben/zoomen. */
class ExtractView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Tool { AI, LASSO, SAMPLE, ERASE, RESTORE }

    interface Listener {
        fun onLassoFinished(path: Path)
        fun onSampleTap(x: Float, y: Float)
        fun onAiTap(x: Float, y: Float)
        fun onStrokeStart()
        fun onBrush(x: Float, y: Float, radius: Float, erase: Boolean)
    }

    var listener: Listener? = null
    var tool: Tool = Tool.LASSO
        set(value) {
            field = value
            invalidate()
        }
    /** Pinseldurchmesser in dp (Bildschirm). */
    var brushSizeDp: Float = 40f
    var showOriginal: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private var result: Bitmap? = null
    private var original: Bitmap? = null
    private val imgMatrix = Matrix()
    private val inverse = Matrix()
    private var needsFit = true

    private val density = resources.displayMetrics.density
    private val checker = CheckerDrawable(10f * density)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ghostPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 70 }
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.WHITE
        pathEffect = DashPathEffect(floatArrayOf(12f * density, 8f * density), 0f)
    }
    private val lassoShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f * density
        color = 0x99000000.toInt()
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.WHITE
    }

    // Touch-Zustand
    private enum class Gesture { NONE, SINGLE, MULTI }
    private var gesture = Gesture.NONE
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var strokeStarted = false
    private val lassoView = Path()
    private val lassoImg = Path()
    private var lassoActive = false
    private var lastDist = 0f
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var cursorVisible = false

    fun setImages(original: Bitmap, result: Bitmap) {
        this.original = original
        this.result = result
        needsFit = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        needsFit = true
    }

    private fun fitIfNeeded() {
        val b = result ?: return
        if (!needsFit || width == 0 || height == 0) return
        val s = min(width.toFloat() / b.width, height.toFloat() / b.height) * 0.96f
        imgMatrix.setScale(s, s)
        imgMatrix.postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f)
        imgMatrix.invert(inverse)
        needsFit = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = result ?: return
        fitIfNeeded()

        val rect = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
        imgMatrix.mapRect(rect)
        checker.setBounds(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        canvas.save()
        canvas.clipRect(rect)
        checker.draw(canvas)
        canvas.restore()

        if (showOriginal) original?.let { canvas.drawBitmap(it, imgMatrix, ghostPaint) }
        canvas.drawBitmap(b, imgMatrix, bitmapPaint)

        if (lassoActive) {
            canvas.drawPath(lassoView, lassoShadow)
            canvas.drawPath(lassoView, lassoPaint)
        }
        if (cursorVisible && (tool == Tool.ERASE || tool == Tool.RESTORE)) {
            canvas.drawCircle(lastX, lastY, brushSizeDp * density / 2f, cursorPaint)
        }
    }

    private fun toImage(x: Float, y: Float): FloatArray {
        val pts = floatArrayOf(x, y)
        inverse.mapPoints(pts)
        return pts
    }

    private fun brushRadiusImage(): Float {
        val scale = imgMatrix.mapRadius(1f)
        return (brushSizeDp * density / 2f) / max(scale, 0.0001f)
    }

    private fun dab(x: Float, y: Float) {
        val p = toImage(x, y)
        listener?.onBrush(p[0], p[1], brushRadiusImage(), tool == Tool.ERASE)
    }

    /** Zeichnet eine durchgehende Linie zwischen zwei Touch-Punkten. */
    private fun dabLine(x0: Float, y0: Float, x1: Float, y1: Float) {
        val step = max(2f, brushSizeDp * density / 6f)
        val dist = hypot(x1 - x0, y1 - y0)
        val n = max(1, (dist / step).toInt())
        for (k in 1..n) {
            val t = k.toFloat() / n
            dab(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (result == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture = Gesture.SINGLE
                downX = e.x; downY = e.y
                lastX = e.x; lastY = e.y
                strokeStarted = false
                cursorVisible = true
                if (tool == Tool.LASSO) {
                    lassoView.reset()
                    lassoView.moveTo(e.x, e.y)
                    lassoActive = true
                }
                invalidate()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {
                    // In den Zoom-Modus wechseln, angefangenes Lasso verwerfen
                    gesture = Gesture.MULTI
                    lassoActive = false
                    lassoView.reset()
                    cursorVisible = false
                    lastDist = dist(e)
                    lastMidX = (e.getX(0) + e.getX(1)) / 2f
                    lastMidY = (e.getY(0) + e.getY(1)) / 2f
                    invalidate()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (gesture == Gesture.MULTI && e.pointerCount >= 2) {
                    val d = dist(e)
                    val mx = (e.getX(0) + e.getX(1)) / 2f
                    val my = (e.getY(0) + e.getY(1)) / 2f
                    if (lastDist > 0f && d > 0f) {
                        val s = d / lastDist
                        val current = imgMatrix.mapRadius(1f)
                        val clamped = (current * s).coerceIn(0.05f, 40f) / current
                        imgMatrix.postScale(clamped, clamped, mx, my)
                    }
                    imgMatrix.postTranslate(mx - lastMidX, my - lastMidY)
                    imgMatrix.invert(inverse)
                    lastDist = d
                    lastMidX = mx
                    lastMidY = my
                    invalidate()
                } else if (gesture == Gesture.SINGLE) {
                    val x = e.x
                    val y = e.y
                    when (tool) {
                        Tool.LASSO -> lassoView.lineTo(x, y)
                        Tool.ERASE, Tool.RESTORE -> {
                            if (!strokeStarted && hypot(x - downX, y - downY) > touchSlop) {
                                strokeStarted = true
                                listener?.onStrokeStart()
                                dab(downX, downY)
                                dabLine(downX, downY, x, y)
                            } else if (strokeStarted) {
                                dabLine(lastX, lastY, x, y)
                            }
                        }
                        Tool.SAMPLE, Tool.AI -> {}
                    }
                    lastX = x
                    lastY = y
                    invalidate()
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (gesture == Gesture.MULTI && e.pointerCount - 1 < 2) {
                    gesture = Gesture.NONE // restliche Finger ignorieren
                }
            }

            MotionEvent.ACTION_UP -> {
                if (gesture == Gesture.SINGLE) {
                    val moved = hypot(e.x - downX, e.y - downY) > touchSlop
                    when (tool) {
                        Tool.LASSO -> {
                            lassoView.lineTo(e.x, e.y)
                            lassoView.close()
                            if (moved) {
                                lassoImg.reset()
                                lassoImg.addPath(lassoView, inverse)
                                listener?.onLassoFinished(Path(lassoImg))
                            }
                        }
                        Tool.SAMPLE -> {
                            if (!moved) {
                                val p = toImage(e.x, e.y)
                                listener?.onSampleTap(p[0], p[1])
                            }
                        }
                        Tool.AI -> {
                            if (!moved) {
                                val p = toImage(e.x, e.y)
                                listener?.onAiTap(p[0], p[1])
                            }
                        }
                        Tool.ERASE, Tool.RESTORE -> {
                            if (!strokeStarted) {
                                listener?.onStrokeStart()
                                dab(e.x, e.y)
                            }
                        }
                    }
                }
                gesture = Gesture.NONE
                lassoActive = false
                lassoView.reset()
                cursorVisible = false
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                gesture = Gesture.NONE
                lassoActive = false
                lassoView.reset()
                cursorVisible = false
                invalidate()
            }
        }
        return true
    }

    private fun dist(e: MotionEvent): Float = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    fun resetView() {
        needsFit = true
        invalidate()
    }
}
