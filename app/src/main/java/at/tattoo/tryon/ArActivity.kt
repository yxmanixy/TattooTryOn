package at.tattoo.tryon

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Bundle
import android.util.Size
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import org.opencv.android.OpenCVLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * AR-Ansicht: Kamerabild + Tattoo.
 * Das Tattoo ist sofort sichtbar. Antippen verankert es an der Hautstelle,
 * danach folgt es dieser Stelle per Bild-Tracking (auch wenn sich die Kamera bewegt).
 */
class ArActivity : AppCompatActivity(), ArOverlayView.Listener, TattooTracker.Listener {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: ArOverlayView
    private lateinit var status: TextView
    private lateinit var btnFreeze: MaterialButton

    private var controller: LifecycleCameraController? = null
    private var tracker: TattooTracker? = null
    private var useFront = false
    private var tattooPath: String? = null
    private var trackingAvailable = false
    private var stable = false
    private var trackGen = -1

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, "Für die AR-Ansicht wird die Kamera benötigt.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        status = findViewById(R.id.status)
        btnFreeze = findViewById(R.id.btnFreeze)

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        overlay.listener = this

        trackingAvailable = try {
            OpenCVLoader.initLocal()
        } catch (t: Throwable) {
            false
        }
        if (!trackingAvailable) {
            Toast.makeText(this, "Tracking nicht verfügbar – Tattoo kann nur manuell platziert werden.", Toast.LENGTH_LONG).show()
        }

        findViewById<MaterialButton>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnRelease).setOnClickListener { releaseTattoo() }
        btnFreeze.setOnClickListener { if (overlay.frozenFrame == null) freeze() else unfreeze() }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { saveJpg() }
        findViewById<MaterialButton>(R.id.btnSwitch).setOnClickListener { switchCamera() }
        findViewById<MaterialButton>(R.id.btnPoints).setOnClickListener {
            overlay.showPoints = !overlay.showPoints
            (it as MaterialButton).text = if (overlay.showPoints) "Punkte aus" else "Punkte"
        }
        findViewById<MaterialButton>(R.id.btnTattoo).setOnClickListener {
            TattooPicker.show(this) { loadTattoo(it.absolutePath) }
        }
        findViewById<Slider>(R.id.sliderOpacity).addOnChangeListener { _, value, _ -> overlay.opacity = value }
        findViewById<Slider>(R.id.sliderCurve).addOnChangeListener { _, value, fromUser ->
            if (fromUser) overlay.setCurvature(value)
        }

        useFront = savedInstanceState?.getBoolean(STATE_FRONT) ?: false
        val path = savedInstanceState?.getString(STATE_TATTOO) ?: intent.getStringExtra(EXTRA_TATTOO)
        if (path != null) loadTattoo(path) else TattooPicker.show(this) { loadTattoo(it.absolutePath) }

        updateStatus()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_FRONT, useFront)
        tattooPath?.let { outState.putString(STATE_TATTOO, it) }
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
                    overlay.setTattoo(bmp)
                    tracker?.active = false
                    updateStatus()
                }
            }
        }
    }

    private fun startCamera() {
        val c = LifecycleCameraController(this)
        c.cameraSelector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        c.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        c.imageAnalysisResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(960, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .build()
        if (trackingAvailable) {
            val t = TattooTracker(this)
            tracker = t
            c.setImageAnalysisAnalyzer(analysisExecutor, t)
        }
        c.bindToLifecycle(this)
        previewView.controller = c
        controller = c
    }

    private fun switchCamera() {
        val c = controller ?: return
        val target = if (useFront) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        try {
            if (c.hasCamera(target)) {
                useFront = !useFront
                c.cameraSelector = target
                releaseTattoo()
            } else {
                Toast.makeText(this, "Keine weitere Kamera gefunden", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Kamera kann nicht gewechselt werden", Toast.LENGTH_SHORT).show()
        }
    }

    private fun releaseTattoo() {
        if (overlay.frozenFrame != null) unfreeze()
        tracker?.active = false
        overlay.setTrackedPoints(null)
        overlay.release()
        updateStatus()
    }

    // ---------- Standbild / Speichern ----------

    private fun freeze(): Boolean {
        val frame: Bitmap? = previewView.bitmap
        if (frame == null) {
            Toast.makeText(this, "Kein Kamerabild verfügbar", Toast.LENGTH_SHORT).show()
            return false
        }
        tracker?.active = false
        overlay.freeze(frame)
        btnFreeze.text = "▶ Live"
        updateStatus()
        return true
    }

    private fun unfreeze() {
        overlay.unfreeze()
        btnFreeze.text = "⏸ Standbild"
        if (overlay.anchored) {
            // Das aktuelle Bild wird zur neuen Ausgangslage
            overlay.resetReference()
            startTracking()
        }
        updateStatus()
    }

    private fun startTracking() {
        val t = tracker ?: return
        t.active = true
        trackGen = t.restart()
    }

    private fun saveJpg() {
        if (overlay.frozenFrame == null && !freeze()) return
        val frame = overlay.frozenFrame ?: return
        val out = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(frame, 0f, 0f, null)
        if (overlay.width > 0 && frame.width != overlay.width) {
            val s = frame.width.toFloat() / overlay.width
            canvas.scale(s, s)
        }
        overlay.drawTattoo(canvas, multiply = true)
        val appCtx = applicationContext
        worker.execute {
            val uri = ImageUtils.saveToGallery(appCtx, out, "tattoo_ar", png = false)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (uri != null) "JPG gespeichert in Bilder/TattooTryOn" else "Speichern fehlgeschlagen",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ---------- Status ----------

    private fun updateStatus() {
        status.text = when {
            overlay.frozenFrame != null ->
                "Standbild – Tattoo kann noch verschoben werden. „Als JPG speichern“ sichert das Bild."
            !overlay.anchored ->
                "Tippe auf die Stelle am Körper, an der das Tattoo sitzen soll. Zwei Finger: drehen/skalieren."
            !trackingAvailable ->
                "Platziert (ohne Tracking)."
            stable ->
                "✓ Verankert – das Tattoo folgt der Hautstelle."
            else ->
                "Verankert – suche Halt im Bild … Kamera ruhig halten."
        }
    }

    // ---------- ArOverlayView.Listener ----------

    override fun onAnchored() {
        startTracking()
        stable = false
        updateStatus()
    }

    override fun onQuadEdited() {
        tracker?.requestReseed()
    }

    // ---------- TattooTracker.Listener (Analyse-Thread) ----------

    override fun onTransform(refToCurrent: Matrix, generation: Int) {
        runOnUiThread {
            if (generation == trackGen) overlay.applyTransform(refToCurrent)
        }
    }

    override fun referenceQuad(): FloatArray? = overlay.referenceQuadForTracking()

    override fun onTrackingState(stable: Boolean) {
        runOnUiThread {
            this.stable = stable
            updateStatus()
        }
    }

    override fun currentQuad(): FloatArray? = overlay.quadForTracking()

    override fun onPoints(points: FloatArray) {
        runOnUiThread { overlay.setTrackedPoints(points) }
    }

    override fun onDestroy() {
        super.onDestroy()
        tracker?.active = false
        controller?.clearImageAnalysisAnalyzer()
        worker.shutdown()
        analysisExecutor.shutdown()
    }

    companion object {
        private const val STATE_FRONT = "front"
        private const val STATE_TATTOO = "tattoo"
    }
}
