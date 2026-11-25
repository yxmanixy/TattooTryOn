package at.tattoo.tryon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Erkennt Körperhaut im Kamerabild mit einem KI-Segmentierungsmodell (MediaPipe, offline).
 * Das Modell unterscheidet Hintergrund, Haare, Körperhaut, Gesichtshaut, Kleidung und Sonstiges –
 * Holzboden, Möbel oder Kleidung werden dadurch NICHT als Haut erkannt.
 */
class BodySegmenter(context: Context) {

    /** Ergebnis: Hautmaske im aufrechten Bild + Umrechnung in Kamerapuffer-Koordinaten. */
    class Seg(
        val skin: BooleanArray,
        val w: Int,
        val h: Int,
        /** Maske -> Kamerapuffer */
        val toBuffer: Matrix,
        /** Kamerapuffer -> Maske */
        val fromBuffer: Matrix,
        val bufferW: Int,
        val bufferH: Int
    ) {
        private val mv = FloatArray(9).also { fromBuffer.getValues(it) }

        /** Ist an dieser Stelle (Puffer- bzw. Bildkoordinaten) Körperhaut? */
        fun skinAt(bx: Float, by: Float): Boolean {
            val x = (mv[0] * bx + mv[1] * by + mv[2]).toInt()
            val y = (mv[3] * bx + mv[4] * by + mv[5]).toInt()
            if (x < 0 || y < 0 || x >= w || y >= h) return false
            return skin[y * w + x]
        }
    }

    private val segmenter: ImageSegmenter? = try {
        val options = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.IMAGE)
            .setOutputCategoryMask(true)
            .setOutputConfidenceMasks(false)
            .build()
        ImageSegmenter.createFromOptions(context, options)
    } catch (t: Throwable) {
        null
    }

    val available: Boolean get() = segmenter != null

    fun segment(image: ImageProxy): Seg? {
        val full = image.toBitmap()
        val r = segmentBitmap(full, image.imageInfo.rotationDegrees)
        full.recycle()
        return r
    }

    /** Segmentiert ein Bitmap. rotationDegrees dreht es vorher aufrecht (Kamera); bei Fotos 0. */
    fun segmentBitmap(full: Bitmap, rotationDegrees: Int): Seg? {
        val seg = segmenter ?: return null
        val bw = full.width
        val bh = full.height
        // Klein rechnen (das Modell arbeitet intern mit 256 x 256)
        val scale = 320f / max(bw, bh)
        val sw = max(8, (bw * scale).roundToInt())
        val sh = max(8, (bh * scale).roundToInt())
        // Aufrecht drehen, damit das Modell Menschen in normaler Lage sieht
        val rot = rotationDegrees
        val m = Matrix()
        m.setScale(sw.toFloat() / bw, sh.toFloat() / bh)
        val uw: Int
        val uh: Int
        when (rot) {
            90 -> {
                m.postRotate(90f); m.postTranslate(sh.toFloat(), 0f); uw = sh; uh = sw
            }
            180 -> {
                m.postRotate(180f); m.postTranslate(sw.toFloat(), sh.toFloat()); uw = sw; uh = sh
            }
            270 -> {
                m.postRotate(270f); m.postTranslate(0f, sw.toFloat()); uw = sh; uh = sw
            }
            else -> {
                uw = sw; uh = sh
            }
        }
        val up = Bitmap.createBitmap(uw, uh, Bitmap.Config.ARGB_8888)
        Canvas(up).drawBitmap(full, m, Paint(Paint.FILTER_BITMAP_FLAG))

        val result = seg.segment(BitmapImageBuilder(up).build())
        up.recycle()
        val opt = result.categoryMask()
        if (!opt.isPresent) return null
        val mask = opt.get()
        val mw = mask.width
        val mh = mask.height
        val buf = ByteBufferExtractor.extract(mask)
        buf.rewind()
        val n = mw * mh
        if (buf.remaining() < n) return null
        val skin = BooleanArray(n)
        var count = 0
        for (i in 0 until n) {
            val c = buf.get(i).toInt() and 0xFF
            if (c == BODY_SKIN || c == FACE_SKIN) {
                skin[i] = true
                count++
            }
        }
        if (mw != uw || mh != uh) m.postScale(mw.toFloat() / uw, mh.toFloat() / uh)
        val inv = Matrix()
        if (!m.invert(inv)) return null
        return Seg(skin, mw, mh, inv, m, bw, bh)
    }

    fun close() {
        try {
            segmenter?.close()
        } catch (t: Throwable) {
        }
    }

    companion object {
        const val MODEL = "selfie_multiclass_256x256.tflite"
        private const val BODY_SKIN = 2
        private const val FACE_SKIN = 3
    }
}
