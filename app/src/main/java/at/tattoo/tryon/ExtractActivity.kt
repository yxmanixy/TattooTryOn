package at.tattoo.tryon

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
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
import androidx.core.content.IntentCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ExtractActivity : AppCompatActivity(), ExtractView.Listener {

    private lateinit var extractView: ExtractView
    private lateinit var hint: TextView
    private lateinit var progress: ProgressBar
    private lateinit var sliderTol: Slider
    private lateinit var sliderSoft: Slider

    private var extractor: Extractor? = null
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var keyVersion = 0
    private var busy = false
    private var currentUri: Uri? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadImage(uri) else if (extractor == null) finish()
    }

    private val keyRunnable = Runnable { runKey() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_extract)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.title = "Tattoo freistellen"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        extractView = findViewById(R.id.extractView)
        hint = findViewById(R.id.hint)
        progress = findViewById(R.id.progress)
        sliderTol = findViewById(R.id.sliderTolerance)
        sliderSoft = findViewById(R.id.sliderSoftness)
        extractView.listener = this

        findViewById<ChipGroup>(R.id.toolGroup).setOnCheckedStateChangeListener { _, ids ->
            extractView.tool = when (ids.firstOrNull()) {
                R.id.toolSample -> ExtractView.Tool.SAMPLE
                R.id.toolErase -> ExtractView.Tool.ERASE
                R.id.toolRestore -> ExtractView.Tool.RESTORE
                else -> ExtractView.Tool.LASSO
            }
            updateHint()
        }
        findViewById<ChipGroup>(R.id.modeGroup).setOnCheckedStateChangeListener { _, ids ->
            val ex = extractor ?: return@setOnCheckedStateChangeListener
            ex.mode = when (ids.firstOrNull()) {
                R.id.modeColor -> Extractor.Mode.COLOR
                R.id.modeInk -> Extractor.Mode.INK
                else -> Extractor.Mode.AUTO
            }
            scheduleKey(0)
            updateHint()
        }
        findViewById<Chip>(R.id.chipOriginal).setOnCheckedChangeListener { _, checked ->
            extractView.showOriginal = checked
        }
        findViewById<Chip>(R.id.chipClearSamples).setOnClickListener {
            val ex = extractor ?: return@setOnClickListener
            ex.clearSamples()
            scheduleKey(0)
            updateHint()
        }
        sliderTol.addOnChangeListener { _, value, _ ->
            extractor?.tolerance = value
            scheduleKey(80)
        }
        sliderSoft.addOnChangeListener { _, value, _ ->
            extractor?.softness = value
            scheduleKey(80)
        }
        findViewById<Slider>(R.id.sliderBrush).addOnChangeListener { _, value, _ ->
            extractView.brushSizeDp = value
        }

        val startUri: Uri? = when {
            intent?.action == Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> intent?.getStringExtra(EXTRA_IMAGE_URI)?.let { Uri.parse(it) }
        }
        val restored = savedInstanceState?.getString(STATE_URI)?.let { Uri.parse(it) }
        when {
            restored != null -> loadImage(restored)
            savedInstanceState != null -> {} // Bildauswahl läuft bereits
            startUri != null -> loadImage(startUri)
            else -> launchPicker()
        }
        updateHint()
    }

    private fun launchPicker() {
        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentUri?.let { outState.putString(STATE_URI, it.toString()) }
    }

    private fun loadImage(uri: Uri) {
        currentUri = uri
        setBusy(true)
        val appCtx = applicationContext
        worker.execute {
            val bmp = ImageUtils.loadBitmap(appCtx, uri, 1600)
            val ex = try {
                if (bmp != null) Extractor(bmp) else null
            } catch (e: OutOfMemoryError) {
                null
            }
            main.post {
                setBusy(false)
                if (isFinishing || isDestroyed) return@post
                if (ex == null) {
                    Toast.makeText(this, "Bild konnte nicht geladen werden", Toast.LENGTH_LONG).show()
                    if (extractor == null) finish()
                    return@post
                }
                ex.tolerance = sliderTol.value
                ex.softness = sliderSoft.value
                extractor = ex
                extractView.setImages(ex.original, ex.result)
                findViewById<Chip>(R.id.toolErase).isChecked = true
                updateHint()
                invalidateOptionsMenu()
                runAutoDetect()
            }
        }
    }

    /** Erkennt das Tattoo automatisch und stellt es frei. */
    private fun runAutoDetect() {
        val ex = extractor ?: return
        setBusy(true)
        hint.text = "Tattoo wird automatisch erkannt …"
        worker.execute {
            val auto = try {
                ex.autoDetect()
            } catch (e: Throwable) {
                null
            }
            main.post {
                setBusy(false)
                if (isDestroyed || extractor !== ex) return@post
                if (auto == null) {
                    Toast.makeText(this, "Automatische Erkennung fehlgeschlagen – bitte manuell umranden.", Toast.LENGTH_LONG).show()
                    findViewById<Chip>(R.id.toolLasso).isChecked = true
                    updateHint()
                    return@post
                }
                ex.applyAuto(auto)
                findViewById<Chip>(R.id.modeAuto).isChecked = true
                ex.mode = Extractor.Mode.AUTO
                scheduleKey(0)
                val msg = when {
                    ex.hasTransparency -> "Bild ist bereits freigestellt."
                    auto.found -> "Tattoo erkannt. Mit Toleranz, Radieren oder Umranden verfeinern."
                    else -> "Kein eindeutiges Tattoo gefunden – bitte mit „Umranden“ markieren."
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                updateHint()
            }
        }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        progress.visibility = if (b) View.VISIBLE else View.GONE
    }

    private fun scheduleKey(delay: Long) {
        main.removeCallbacks(keyRunnable)
        main.postDelayed(keyRunnable, delay)
    }

    private fun runKey() {
        val ex = extractor ?: return
        val params = ex.params()
        val version = ++keyVersion
        worker.execute {
            val res = ex.computeKey(params)
            main.post {
                if (version == keyVersion && extractor === ex && !isDestroyed) {
                    ex.applyKey(res)
                    ex.composeAll()
                    extractView.invalidate()
                }
            }
        }
    }

    private fun updateHint() {
        val ex = extractor
        hint.text = when {
            ex == null -> "Bild wird geladen …"
            extractView.tool == ExtractView.Tool.LASSO ->
                "Umranden: Fahre einmal rund um das Tattoo, falls die automatische Erkennung danebenliegt. Zwei Finger = zoomen."
            extractView.tool == ExtractView.Tool.SAMPLE ->
                "Tippe auf freie Haut neben dem Tattoo (auch mehrfach: hell, dunkel). Proben: ${ex.samples.size}"
            extractView.tool == ExtractView.Tool.ERASE ->
                "Übermale Reste, die weg sollen. Zwei Finger = zoomen."
            else ->
                "Übermale Stellen des Tattoos, die versehentlich entfernt wurden."
        }
    }

    // ---------- ExtractView.Listener ----------

    override fun onLassoFinished(path: Path) {
        val ex = extractor ?: return
        ex.applyLasso(path)
        if (ex.mode != Extractor.Mode.AUTO && ex.samples.isEmpty()) {
            val skin = ex.sampleAlongPath(path)
            if (skin != null) {
                ex.samples.add(skin)
                Toast.makeText(
                    this,
                    "Hautfarbe erkannt. Mit „Haut antippen“ und „Toleranz“ verfeinern.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        scheduleKey(0)
        extractView.invalidate()
        updateHint()
    }

    override fun onSampleTap(x: Float, y: Float) {
        val ex = extractor ?: return
        val c = ex.averageAt(x.toInt(), y.toInt()) ?: return
        if (ex.mode == Extractor.Mode.AUTO) {
            // Antippen nutzt die Hautproben -> in den Modus "Tinte" wechseln
            findViewById<Chip>(R.id.modeInk).isChecked = true
            ex.mode = Extractor.Mode.INK
        }
        ex.addSample(c)
        scheduleKey(0)
        updateHint()
    }

    override fun onStrokeStart() {
        extractor?.beginStroke()
    }

    override fun onBrush(x: Float, y: Float, radius: Float, erase: Boolean) {
        val ex = extractor ?: return
        if (ex.brush(x, y, radius, erase) != null) extractView.invalidate()
    }

    // ---------- Menü ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_extract, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val ex = extractor
        when (item.itemId) {
            android.R.id.home -> {
                finish(); return true
            }
            R.id.action_undo -> {
                if (ex != null && ex.undo()) {
                    scheduleKey(0)
                    extractView.invalidate()
                    updateHint()
                } else {
                    Toast.makeText(this, "Nichts zum Rückgängigmachen", Toast.LENGTH_SHORT).show()
                }
                return true
            }
            R.id.action_save -> {
                save(); return true
            }
            R.id.action_auto -> {
                runAutoDetect(); return true
            }
            R.id.action_new -> {
                launchPicker(); return true
            }
            R.id.action_reset -> {
                ex?.resetAll()
                scheduleKey(0)
                extractView.invalidate()
                updateHint()
                return true
            }
            R.id.action_fit -> {
                extractView.resetView(); return true
            }
            R.id.action_help -> {
                showHelp(); return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle("So stellst du ein Tattoo frei")
            .setMessage(
                "Das Tattoo wird beim Öffnen automatisch erkannt und freigestellt (Modus Auto).\n\n" +
                    "1. Umranden: Liegt die Erkennung daneben, fahre einmal rund um das Tattoo. " +
                    "Alles außerhalb wird entfernt.\n\n" +
                    "2. Haut antippen: Tippe zusätzlich auf helle und schattige Hautstellen, " +
                    "damit die Haut sauber verschwindet.\n\n" +
                    "3. Toleranz erhöhen, bis die Haut weg ist, aber die Linien bleiben. " +
                    "„Weichheit“ macht die Kanten weicher.\n\n" +
                    "Modus Tinte: ideal für schwarze/graue Tattoos.\n" +
                    "Modus Farbe: für bunte Tattoos oder Motive auf weißem Papier.\n\n" +
                    "4. Mit Radieren / Zurückholen nacharbeiten. Mit zwei Fingern zoomen.\n\n" +
                    "5. Speichern."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun save() {
        val ex = extractor ?: return
        if (busy) return
        val snapshot: Bitmap = ex.result.copy(Bitmap.Config.ARGB_8888, false)
        setBusy(true)
        val appCtx = applicationContext
        worker.execute {
            val trimmed = ImageUtils.trimTransparent(snapshot)
            val file = trimmed?.let { TattooStore.save(appCtx, it) }
            main.post {
                setBusy(false)
                if (isFinishing || isDestroyed) return@post
                if (file == null) {
                    Toast.makeText(this, "Das Bild ist komplett leer – nichts zu speichern.", Toast.LENGTH_LONG).show()
                    return@post
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle("Tattoo gespeichert")
                    .setMessage("Wie möchtest du es ansehen?")
                    .setPositiveButton("Auf Foto anprobieren") { _, _ ->
                        startActivity(Intent(this, TryOnActivity::class.java).putExtra(EXTRA_TATTOO, file.absolutePath))
                        finish()
                    }
                    .setNeutralButton("AR-Kamera") { _, _ ->
                        startActivity(Intent(this, ArActivity::class.java).putExtra(EXTRA_TATTOO, file.absolutePath))
                        finish()
                    }
                    .setNegativeButton("Fertig") { _, _ -> finish() }
                    .show()
            }
        }
    }

    companion object {
        private const val STATE_URI = "state_uri"
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        worker.shutdown()
    }
}
