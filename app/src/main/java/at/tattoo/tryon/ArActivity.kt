package at.tattoo.tryon

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * AR-Live-Ansicht: Kamerabild + Körpererkennung (ML Kit Pose Detection).
 * Das Tattoo wird an einem gewählten Körperteil verankert und bewegt sich mit.
 */
class ArActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: ArOverlayView
    private lateinit var status: TextView
    private lateinit var btnAnchor: MaterialButton

    private var controller: LifecycleCameraController? = null
    private var poseDetector: PoseDetector? = null
    private var useFront = false
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

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
        btnAnchor = findViewById(R.id.btnAnchor)

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        overlay.onTrackingChanged = { updateStatus(it) }

        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }
        btnAnchor.setOnClickListener { chooseAnchor() }
        findViewById<Slider>(R.id.sliderOpacity).addOnChangeListener { _, value, _ -> overlay.opacity = value }
        findViewById<MaterialButton>(R.id.btnSnapshot).setOnClickListener { snapshot() }
        findViewById<MaterialButton>(R.id.btnSwitch).setOnClickListener { switchCamera() }
        findViewById<MaterialButton>(R.id.btnTattoo).setOnClickListener {
            TattooPicker.show(this) { loadTattoo(it.absolutePath) }
        }
        findViewById<Chip>(R.id.chipSkeleton).setOnCheckedChangeListener { _, checked -> overlay.showSkeleton = checked }
        findViewById<Chip>(R.id.chipReset).setOnClickListener { overlay.resetAdjustments() }

        val savedAnchor = savedInstanceState?.getString(STATE_ANCHOR)
        val anchor = BodyAnchor.values().firstOrNull { it.name == savedAnchor } ?: BodyAnchor.LEFT_FOREARM
        setAnchor(anchor)
        useFront = savedInstanceState?.getBoolean(STATE_FRONT) ?: false

        val path = savedInstanceState?.getString(STATE_TATTOO) ?: intent.getStringExtra(EXTRA_TATTOO)
        if (path != null) loadTattoo(path) else TattooPicker.show(this) { loadTattoo(it.absolutePath) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private var tattooPath: String? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ANCHOR, overlay.anchor.name)
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
                    overlay.tattoo = bmp
                }
            }
        }
    }

    private fun startCamera() {
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
        val detector = PoseDetection.getClient(options)
        poseDetector = detector

        val c = LifecycleCameraController(this)
        c.cameraSelector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val mainExecutor = ContextCompat.getMainExecutor(this)
        c.setImageAnalysisAnalyzer(
            mainExecutor,
            MlKitAnalyzer(
                listOf(detector),
                CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
                mainExecutor
            ) { result: MlKitAnalyzer.Result? ->
                val pose = result?.getValue(detector)
                overlay.updatePose(pose)
            }
        )
        c.bindToLifecycle(this)
        previewView.controller = c
        controller = c
        updateStatus(false)
    }

    private fun switchCamera() {
        val c = controller ?: return
        val target = if (useFront) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        try {
            if (c.hasCamera(target)) {
                useFront = !useFront
                c.cameraSelector = target
            } else {
                Toast.makeText(this, "Keine weitere Kamera gefunden", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Kamera kann nicht gewechselt werden", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setAnchor(a: BodyAnchor) {
        overlay.anchor = a
        btnAnchor.text = "Körperteil: ${a.label}"
        updateStatus(false)
    }

    private fun chooseAnchor() {
        val all = BodyAnchor.values()
        val labels = all.map { it.label }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Wo soll das Tattoo sitzen?")
            .setSingleChoiceItems(labels, all.indexOf(overlay.anchor)) { d, which ->
                setAnchor(all[which])
                d.dismiss()
            }
            .show()
    }

    private fun updateStatus(tracked: Boolean) {
        val a = overlay.anchor
        status.text = when {
            !a.tracked -> "Freier Modus: Tattoo mit Fingern platzieren, skalieren und drehen."
            tracked -> "✓ ${a.label} erkannt – 1 Finger verschiebt, 2 Finger drehen/skalieren."
            a.torso -> "Suche Oberkörper … Schultern (und Hüfte) ins Bild bringen."
            else -> "Suche ${a.label} … etwas Abstand halten, damit Schulter, Ellbogen bzw. Knie sichtbar sind."
        }
    }

    /** Speichert das aktuelle Kamerabild mit Tattoo in der Galerie. */
    private fun snapshot() {
        val frame: Bitmap? = previewView.bitmap
        if (frame == null) {
            Toast.makeText(this, "Kein Kamerabild verfügbar", Toast.LENGTH_SHORT).show()
            return
        }
        val out = frame.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        if (out.width != overlay.width && overlay.width > 0) {
            val s = out.width.toFloat() / overlay.width
            canvas.scale(s, s)
        }
        overlay.drawTattooOn(canvas, multiply = true)
        val appCtx = applicationContext
        worker.execute {
            val uri = ImageUtils.saveToGallery(appCtx, out, "ar_tattoo", png = false)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (uri != null) "Foto gespeichert in Bilder/TattooTryOn" else "Speichern fehlgeschlagen",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controller?.clearImageAnalysisAnalyzer()
        poseDetector?.close()
        worker.shutdown()
    }

    companion object {
        private const val STATE_ANCHOR = "anchor"
        private const val STATE_FRONT = "front"
        private const val STATE_TATTOO = "tattoo"
    }
}
