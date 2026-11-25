package at.tattoo.tryon

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * KI-Auswahl per Antippen (MediaPipe "Magic Touch", offline):
 * Das Modell liefert das Objekt, auf das getippt wurde, als Maske.
 */
class TattooSelector(context: Context) {

    private val segmenter: InteractiveSegmenter? = try {
        val options = InteractiveSegmenter.InteractiveSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setOutputCategoryMask(false)
            .setOutputConfidenceMasks(true)
            .build()
        InteractiveSegmenter.createFromOptions(context, options)
    } catch (t: Throwable) {
        null
    }

    val available: Boolean get() = segmenter != null

    /** Maske des angetippten Objekts; liefert eine Abfrage-Funktion in Bildkoordinaten von [src]. */
    fun select(src: Bitmap, x: Float, y: Float): ((Float, Float) -> Boolean)? {
        val seg = segmenter ?: return null
        val scale = minOf(1f, 512f / max(src.width, src.height))
        val sw = max(8, (src.width * scale).roundToInt())
        val sh = max(8, (src.height * scale).roundToInt())
        val small = if (scale < 1f) Bitmap.createScaledBitmap(src, sw, sh, true) else src
        val argb = if (small.config == Bitmap.Config.ARGB_8888) small else small.copy(Bitmap.Config.ARGB_8888, false)
        val roi = InteractiveSegmenter.RegionOfInterest.create(
            NormalizedKeypoint.create((x / src.width).coerceIn(0f, 1f), (y / src.height).coerceIn(0f, 1f))
        )
        val result = seg.segment(BitmapImageBuilder(argb).build(), roi)
        if (argb !== small) argb.recycle()
        if (small !== src) small.recycle()
        val masks = result.confidenceMasks()
        if (!masks.isPresent || masks.get().isEmpty()) return null
        val mask = masks.get().last()
        val mw = mask.width
        val mh = mask.height
        val buf = ByteBufferExtractor.extract(mask).order(ByteOrder.nativeOrder())
        buf.rewind()
        val n = mw * mh
        if (buf.remaining() < n * 4) return null
        val sel = BooleanArray(n)
        var count = 0
        for (i in 0 until n) {
            if (buf.getFloat(i * 4) > 0.5f) {
                sel[i] = true
                count++
            }
        }
        if (count < 10) return null
        val fx = mw.toFloat() / src.width
        val fy = mh.toFloat() / src.height
        return { bx, by ->
            val ix = (bx * fx).toInt()
            val iy = (by * fy).toInt()
            ix >= 0 && iy >= 0 && ix < mw && iy < mh && sel[iy * mw + ix]
        }
    }

    fun close() {
        try {
            segmenter?.close()
        } catch (t: Throwable) {
        }
    }

    companion object {
        const val MODEL = "magic_touch.tflite"
    }
}
