package at.tattoo.tryon

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

const val EXTRA_TATTOO = "extra_tattoo_path"
const val EXTRA_IMAGE_URI = "extra_image_uri"

/** Laden und Speichern von Bildern. */
object ImageUtils {

    /** Lädt ein Bild verkleinert (längste Seite <= maxSide), korrigiert die EXIF-Drehung, liefert ein veränderbares ARGB-Bitmap. */
    fun loadBitmap(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        return try {
            val cr = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bmp: Bitmap = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null

            val orientation = try {
                cr.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    m.postRotate(90f); m.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    m.postRotate(270f); m.postScale(-1f, 1f)
                }
            }
            val longest = max(bmp.width, bmp.height)
            if (longest > maxSide) {
                val s = maxSide.toFloat() / longest
                m.postScale(s, s)
            }
            if (!m.isIdentity) {
                val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (r != bmp) bmp.recycle()
                bmp = r
            }
            if (!bmp.isMutable || bmp.config != Bitmap.Config.ARGB_8888) {
                val c = bmp.copy(Bitmap.Config.ARGB_8888, true)
                bmp.recycle()
                bmp = c
            }
            bmp
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Speichert ein Bild in der Galerie (Bilder/TattooTryOn). */
    fun saveToGallery(context: Context, bitmap: Bitmap, baseName: String, png: Boolean): Uri? {
        val resolver = context.contentResolver
        val name = baseName + "_" + System.currentTimeMillis() + if (png) ".png" else ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, if (png) "image/png" else "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TattooTryOn")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(
                    if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                    if (png) 100 else 93,
                    out
                )
            } ?: throw IOException("Kein Ausgabestrom")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /** Schneidet transparente Ränder ab (mit etwas Rand). */
    fun trimTransparent(src: Bitmap, padding: Int = 8): Bitmap? {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((px[row + x] ushr 24) > 8) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null
        minX = max(0, minX - padding)
        minY = max(0, minY - padding)
        maxX = min(w - 1, maxX + padding)
        maxY = min(h - 1, maxY + padding)
        return Bitmap.createBitmap(src, minX, minY, maxX - minX + 1, maxY - minY + 1)
    }
}

/** Ablage der freigestellten Tattoos (PNG mit Transparenz) im App-Speicher. */
object TattooStore {
    private fun dir(ctx: Context): File = File(ctx.filesDir, "tattoos").apply { mkdirs() }

    fun save(ctx: Context, bmp: Bitmap): File {
        val f = File(dir(ctx), "tattoo_${System.currentTimeMillis()}.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    fun list(ctx: Context): List<File> =
        (dir(ctx).listFiles()?.toList() ?: emptyList())
            .filter { it.name.endsWith(".png", ignoreCase = true) }
            .sortedByDescending { it.lastModified() }

    fun load(path: String): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        BitmapFactory.decodeFile(path, opts)
    } catch (e: Exception) {
        null
    }

    fun thumbnail(file: File, target: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        null
    }
}

/** Schachbrettmuster als Hintergrund für transparente Bereiche. */
class CheckerDrawable(private val tile: Float) : Drawable() {
    private val light = Paint().apply { color = 0xFF3A3A3A.toInt() }
    private val dark = Paint().apply { color = 0xFF2A2A2A.toInt() }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawRect(b, dark)
        var y = b.top.toFloat()
        var row = 0
        while (y < b.bottom) {
            var x = b.left.toFloat() + if (row % 2 == 0) 0f else tile
            while (x < b.right) {
                canvas.drawRect(x, y, min(x + tile, b.right.toFloat()), min(y + tile, b.bottom.toFloat()), light)
                x += tile * 2
            }
            y += tile
            row++
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/** Quadratische Bildkachel. */
class SquareImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredWidth)
    }
}

/** Raster der gespeicherten Tattoos. */
class TattooAdapter(private val onClick: (File) -> Unit) : RecyclerView.Adapter<TattooAdapter.Holder>() {
    private var files: List<File> = emptyList()

    class Holder(val image: SquareImageView) : RecyclerView.ViewHolder(image)

    fun submit(newFiles: List<File>) {
        files = newFiles
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tattoo, parent, false) as SquareImageView
        val density = parent.resources.displayMetrics.density
        v.background = CheckerDrawable(8f * density)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val f = files[position]
        holder.image.setImageBitmap(TattooStore.thumbnail(f, 300))
        holder.image.setOnClickListener { onClick(f) }
    }

    override fun getItemCount(): Int = files.size
}

/** Dialog zur Auswahl eines gespeicherten Tattoos. */
object TattooPicker {
    fun show(activity: AppCompatActivity, onPicked: (File) -> Unit) {
        val files = TattooStore.list(activity)
        if (files.isEmpty()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("Noch keine Tattoos")
                .setMessage("Stelle zuerst auf dem Startbildschirm ein Tattoo frei.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val rv = RecyclerView(activity)
        val pad = (12 * activity.resources.displayMetrics.density).toInt()
        rv.setPadding(pad, pad, pad, pad)
        rv.layoutManager = GridLayoutManager(activity, 3)
        var dialog: AlertDialog? = null
        val adapter = TattooAdapter { f ->
            dialog?.dismiss()
            onPicked(f)
        }
        rv.adapter = adapter
        adapter.submit(files)
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Tattoo wählen")
            .setView(rv)
            .setNegativeButton("Abbrechen", null)
            .show()
    }
}
