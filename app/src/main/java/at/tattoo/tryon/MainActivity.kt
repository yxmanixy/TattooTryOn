package at.tattoo.tryon

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: TattooAdapter
    private lateinit var emptyText: TextView

    private val importLauncher = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) importAll(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.title = getString(R.string.app_name)

        findViewById<MaterialButton>(R.id.btnImport).setOnClickListener {
            importLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<MaterialButton>(R.id.btnExtract).setOnClickListener {
            startActivity(Intent(this, ExtractActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnTryOn).setOnClickListener {
            pickTattooThen { openTryOn(it) }
        }
        findViewById<MaterialButton>(R.id.btnAr).setOnClickListener {
            pickTattooThen { openAr(it) }
        }

        emptyText = findViewById(R.id.emptyText)
        val rv = findViewById<RecyclerView>(R.id.tattooList)
        rv.layoutManager = GridLayoutManager(this, 3)
        adapter = TattooAdapter { showTattooOptions(it) }
        rv.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val files = TattooStore.list(this)
        adapter.submit(files)
        emptyText.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openTryOn(f: File) {
        startActivity(Intent(this, TryOnActivity::class.java).putExtra(EXTRA_TATTOO, f.absolutePath))
    }

    private fun openAr(f: File) {
        startActivity(Intent(this, ArActivity::class.java).putExtra(EXTRA_TATTOO, f.absolutePath))
    }

    private fun pickTattooThen(action: (File) -> Unit) {
        val files = TattooStore.list(this)
        when {
            files.isEmpty() -> MaterialAlertDialogBuilder(this)
                .setTitle("Noch kein Tattoo")
                .setMessage("Stelle zuerst ein Tattoo aus einem Foto frei.")
                .setPositiveButton("Jetzt freistellen") { _, _ ->
                    startActivity(Intent(this, ExtractActivity::class.java))
                }
                .setNegativeButton("Abbrechen", null)
                .show()
            files.size == 1 -> action(files[0])
            else -> TattooPicker.show(this, action)
        }
    }

    private fun showTattooOptions(file: File) {
        val items = arrayOf(
            "Auf Körperfoto anprobieren",
            "AR-Live-Ansicht",
            "Nachbearbeiten (Radieren, Zurückholen …)",
            "Als PNG in Galerie speichern",
            "Löschen"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Tattoo")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openTryOn(file)
                    1 -> openAr(file)
                    2 -> startActivity(
                        Intent(this, ExtractActivity::class.java)
                            .putExtra(EXTRA_IMAGE_URI, Uri.fromFile(file).toString())
                    )
                    3 -> exportToGallery(file)
                    4 -> confirmDelete(file)
                }
            }
            .show()
    }

    /** Mehrere Fotos importieren: Tattoo jeweils automatisch erkennen und freistellen. */
    private fun importAll(uris: List<Uri>) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val label = TextView(this).apply { text = "Starte …" }
        val bar = LinearProgressIndicator(this).apply {
            isIndeterminate = false
            max = uris.size
        }
        box.addView(label)
        box.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = pad / 2
            }
        )
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Tattoos werden erkannt")
            .setView(box)
            .setCancelable(false)
            .show()
        val appCtx = applicationContext
        Executors.newSingleThreadExecutor().execute {
            var ok = 0
            uris.forEachIndexed { idx, uri ->
                runOnUiThread {
                    label.text = "Bild ${idx + 1} von ${uris.size}: Tattoo wird gesucht und freigestellt …"
                    bar.setProgressCompat(idx, true)
                }
                try {
                    val bmp = ImageUtils.loadBitmap(appCtx, uri, 1600)
                    if (bmp != null) {
                        val cut = Extractor.autoExtract(bmp)
                        if (cut != null) {
                            TattooStore.save(appCtx, cut)
                            cut.recycle()
                            ok++
                        }
                        bmp.recycle()
                    }
                } catch (e: Throwable) {
                    // Bild überspringen
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                dialog.dismiss()
                refresh()
                Toast.makeText(
                    this,
                    "$ok von ${uris.size} Tattoos hinzugefügt. Tippe auf ein Tattoo, um es nachzubearbeiten.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun exportToGallery(file: File) {
        val appCtx = applicationContext
        Executors.newSingleThreadExecutor().execute {
            val bmp = TattooStore.load(file.absolutePath)
            val uri = if (bmp != null) ImageUtils.saveToGallery(appCtx, bmp, "tattoo", png = true) else null
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (uri != null) "In Galerie gespeichert (Bilder/TattooTryOn)" else "Speichern fehlgeschlagen",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun confirmDelete(file: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Tattoo löschen?")
            .setPositiveButton("Löschen") { _, _ ->
                file.delete()
                refresh()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
}
