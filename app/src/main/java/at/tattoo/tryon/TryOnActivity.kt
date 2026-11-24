package at.tattoo.tryon

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TryOnActivity : AppCompatActivity() {

    private lateinit var view: TryOnView
    private lateinit var progress: ProgressBar
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var pendingPhoto: Uri? = null
    private var bodyUri: Uri? = null
    private var tattooPath: String? = null

    // Körperform
    private val fitExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var fitVersion = 0
    private var form = 0f
    private var folds = 0f
    private lateinit var shapeInfo: TextView
    private val fitRunnable = Runnable { runFit() }

    // Farbe
    private var inkAmount = 0f
    private var saturation = 1f
    private var brightness = 0f
    private var skinColor = Color.rgb(205, 160, 135)

    private val pickBody = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadBody(uri)
    }

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingPhoto
        if (ok && uri != null) loadBody(uri)
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(this, "Ohne Kamerazugriff bitte ein Foto aus der Galerie wählen.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tryon)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.title = "Foto-Anprobe"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        view = findViewById(R.id.tryOnView)
        progress = findViewById(R.id.progress)

        findViewById<MaterialButton>(R.id.btnCamera).setOnClickListener { startCamera() }
        findViewById<MaterialButton>(R.id.btnGallery).setOnClickListener {
            pickBody.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<MaterialButton>(R.id.btnTattoo).setOnClickListener {
            TattooPicker.show(this) { loadTattoo(it.absolutePath) }
        }
        findViewById<Slider>(R.id.sliderOpacity).addOnChangeListener { _, value, _ -> view.opacity = value }
        findViewById<Chip>(R.id.chipMultiply).setOnCheckedChangeListener { _, checked -> view.multiply = checked }
        findViewById<Chip>(R.id.chipFlip).setOnClickListener { view.flipTattoo() }
        findViewById<Chip>(R.id.chipReset).setOnClickListener { view.resetTattoo() }

        // Körperform- und Farb-Panel
        shapeInfo = findViewById(R.id.shapeInfo)
        val panelShape = findViewById<View>(R.id.panelShape)
        val panelColor = findViewById<View>(R.id.panelColor)
        val sliderForm = findViewById<Slider>(R.id.sliderForm)
        val sliderFolds = findViewById<Slider>(R.id.sliderFolds)
        val sliderInk = findViewById<Slider>(R.id.sliderInk)
        findViewById<ChipGroup>(R.id.panelGroup).setOnCheckedStateChangeListener { _, ids ->
            val id = ids.firstOrNull()
            panelShape.visibility = if (id == R.id.chipShape) View.VISIBLE else View.GONE
            panelColor.visibility = if (id == R.id.chipColor) View.VISIBLE else View.GONE
            if (id == R.id.chipShape && sliderForm.value == 0f && sliderFolds.value == 0f) {
                // Beim ersten Öffnen gleich einen sichtbaren Effekt zeigen
                sliderForm.value = 0.6f
                sliderFolds.value = 0.4f
            }
            if (id == R.id.chipColor && sliderInk.value == 0f) sliderInk.value = 0.5f
        }
        sliderForm.addOnChangeListener { _, value, _ ->
            form = value
            scheduleFit()
        }
        sliderFolds.addOnChangeListener { _, value, _ ->
            folds = value
            scheduleFit()
        }
        sliderInk.addOnChangeListener { _, value, _ ->
            inkAmount = value
            updateInk()
        }
        findViewById<Slider>(R.id.sliderSat).addOnChangeListener { _, value, _ ->
            saturation = value
            updateInk()
        }
        findViewById<Slider>(R.id.sliderBright).addOnChangeListener { _, value, _ ->
            brightness = value
            updateInk()
        }
        view.onTransformChanged = {
            updateSkin()
            scheduleFit()
        }

        pendingPhoto = savedInstanceState?.getString(STATE_PENDING)?.let { Uri.parse(it) }
        val path = savedInstanceState?.getString(STATE_TATTOO) ?: intent.getStringExtra(EXTRA_TATTOO)
        if (path != null) loadTattoo(path)
        savedInstanceState?.getString(STATE_BODY)?.let { loadBody(Uri.parse(it)) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingPhoto?.let { outState.putString(STATE_PENDING, it.toString()) }
        bodyUri?.let { outState.putString(STATE_BODY, it.toString()) }
        tattooPath?.let { outState.putString(STATE_TATTOO, it) }
    }

    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        try {
            val dir = File(cacheDir, "photos").apply { mkdirs() }
            val file = File(dir, "body_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            pendingPhoto = uri
            takePhoto.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "Kamera-App konnte nicht geöffnet werden", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadTattoo(path: String) {
        worker.execute {
            val bmp = TattooStore.load(path)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (bmp == null) {
                    Toast.makeText(this, "Tattoo konnte nicht geladen werden", Toast.LENGTH_SHORT).show()
                } else {
                    tattooPath = path
                    view.setTattoo(bmp)
                }
            }
        }
    }

    private fun loadBody(uri: Uri) {
        progress.visibility = View.VISIBLE
        val appCtx = applicationContext
        worker.execute {
            val bmp = ImageUtils.loadBitmap(appCtx, uri, 2048)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                if (bmp == null) {
                    Toast.makeText(this, "Foto konnte nicht geladen werden", Toast.LENGTH_SHORT).show()
                } else {
                    bodyUri = uri
                    view.setBody(bmp)
                    if (view.tattoo == null) {
                        TattooPicker.show(this) { loadTattoo(it.absolutePath) }
                    }
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_save, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish(); return true
            }
            R.id.action_save -> {
                save(); return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun save() {
        val composite = view.renderComposite()
        if (composite == null) {
            Toast.makeText(this, "Zuerst ein Körperfoto wählen", Toast.LENGTH_SHORT).show()
            return
        }
        progress.visibility = View.VISIBLE
        val appCtx = applicationContext
        worker.execute {
            val uri = ImageUtils.saveToGallery(appCtx, composite, "anprobe", png = false)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                Toast.makeText(
                    this,
                    if (uri != null) "Gespeichert in Bilder/TattooTryOn" else "Speichern fehlgeschlagen",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ---------- Körperform ----------

    private fun scheduleFit() {
        fitVersion++
        main.removeCallbacks(fitRunnable)
        main.postDelayed(fitRunnable, 250)
    }

    private fun runFit() {
        val body = view.body ?: return
        val t = view.tattoo ?: return
        if (form <= 0.001f && folds <= 0.001f) {
            view.setFit(null, null)
            shapeInfo.text = "Rundung, Schatten und Falten werden aus dem Foto berechnet."
            return
        }
        val m = view.tattooMatrixCopy()
        val f = form
        val k = folds
        val version = fitVersion
        shapeInfo.text = "Körperform wird berechnet …"
        fitExecutor.execute {
            val res = try {
                ShapeFit.compute(body, t, m, f, k)
            } catch (e: Throwable) {
                null
            }
            main.post {
                if (isDestroyed || version != fitVersion) return@post
                if (res != null) {
                    view.setFit(res.layer, res.layerToBody)
                    shapeInfo.text = "Rundung, Schatten und Falten aus dem Foto übernommen."
                } else {
                    shapeInfo.text = "Körperform konnte hier nicht berechnet werden."
                }
            }
        }
    }

    // ---------- Farbe ----------

    /** Hautfarbe unter dem Tattoo bestimmen (für die Farbangleichung). */
    private fun updateSkin() {
        val body = view.body ?: return
        val c = view.tattooCenterInBody() ?: return
        val r = (c[2] * 1.3f).toInt().coerceIn(8, 160)
        skinColor = ShapeFit.skinAround(body, c[0].toInt(), c[1].toInt(), r)
        updateInk()
    }

    /**
     * Echte Tattoo-Tinte liegt unter der Haut: weniger gesättigt, Schwarz wirkt leicht
     * bläulich-grau, Kontrast geringer und der Hautton scheint etwas durch.
     */
    private fun updateInk() {
        if (inkAmount <= 0.001f && kotlin.math.abs(saturation - 1f) < 0.001f && kotlin.math.abs(brightness) < 0.001f) {
            view.inkFilter = null
            return
        }
        val t = inkAmount
        val cm = ColorMatrix()
        cm.setSaturation((saturation * (1f - 0.45f * t)).coerceAtLeast(0f))
        val mix = 0.22f * t
        val k = (1f - 0.22f * t) * (1f - mix)
        val lift = 16f * t + brightness * 255f
        val m2 = ColorMatrix(
            floatArrayOf(
                k, 0f, 0f, 0f, Color.red(skinColor) * mix + lift - 6f * t,
                0f, k, 0f, 0f, Color.green(skinColor) * mix + lift + 2f * t,
                0f, 0f, k, 0f, Color.blue(skinColor) * mix + lift + 10f * t,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(m2)
        view.inkFilter = ColorMatrixColorFilter(cm)
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        worker.shutdown()
        fitExecutor.shutdown()
    }

    companion object {
        private const val STATE_PENDING = "pending_photo"
        private const val STATE_BODY = "body_uri"
        private const val STATE_TATTOO = "tattoo_path"
    }
}
