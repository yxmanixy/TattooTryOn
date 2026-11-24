package at.tattoo.tryon

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
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

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    companion object {
        private const val STATE_PENDING = "pending_photo"
        private const val STATE_BODY = "body_uri"
        private const val STATE_TATTOO = "tattoo_path"
    }
}
