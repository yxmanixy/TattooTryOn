package at.tattoo.tryon

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: TattooAdapter
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.title = getString(R.string.app_name)

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
            "Als PNG in Galerie speichern",
            "Löschen"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Tattoo")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openTryOn(file)
                    1 -> openAr(file)
                    2 -> exportToGallery(file)
                    3 -> confirmDelete(file)
                }
            }
            .show()
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
