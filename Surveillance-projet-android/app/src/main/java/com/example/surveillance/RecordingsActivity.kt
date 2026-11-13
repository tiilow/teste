package com.example.surveillance

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class RecordingsActivity : ComponentActivity() {

    private class Item(
        val id: Long, val uri: Uri, val dateMs: Long, val durMs: Long, val size: Long
    )

    private class Holder(
        val check: CheckBox, val thumb: ImageView,
        val title: TextView, val sub: TextView, var id: Long = -1
    )

    private var items: List<Item> = emptyList()
    private val selected = HashSet<Long>()
    private val thumbs = LruCache<Long, Bitmap>(80)
    private val pool = Executors.newFixedThreadPool(2)
    private val dateFmt = SimpleDateFormat("EEE dd/MM/yyyy 'à' HH:mm:ss", Locale.getDefault())

    private lateinit var list: ListView
    private lateinit var title: TextView
    private lateinit var selectAll: Button
    private lateinit var deleteBtn: Button
    private val adapter = Adapter()

    private val readPerm: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { load() }

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recordings)
        list = findViewById(R.id.list)
        title = findViewById(R.id.title)
        selectAll = findViewById(R.id.selectAll)
        deleteBtn = findViewById(R.id.deleteBtn)
        list.adapter = adapter
        list.emptyView = findViewById(R.id.empty)

        list.setOnItemClickListener { _, _, pos, _ ->
            val id = items[pos].id
            if (!selected.remove(id)) selected.add(id)
            adapter.notifyDataSetChanged()
            updateButtons()
        }
        selectAll.setOnClickListener {
            if (items.isNotEmpty() && selected.size == items.size) selected.clear()
            else { selected.clear(); items.forEach { selected.add(it.id) } }
            adapter.notifyDataSetChanged()
            updateButtons()
        }
        deleteBtn.setOnClickListener { confirmDelete() }

        if (checkSelfPermission(readPerm) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(readPerm)
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        super.onDestroy()
        pool.shutdown()
    }

    // ---------- Chargement ----------

    private fun load() {
        Thread {
            val result = ArrayList<Item>()
            try {
                val proj = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE
                )
                contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj,
                    MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?",
                    arrayOf("Movies/Surveillance%"),
                    MediaStore.Video.Media.DATE_ADDED + " DESC"
                )?.use { c ->
                    val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val iDate = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                    val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    while (c.moveToNext()) {
                        val id = c.getLong(iId)
                        val uri = android.content.ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                        )
                        result.add(
                            Item(id, uri, c.getLong(iDate) * 1000, c.getLong(iDur), c.getLong(iSize))
                        )
                    }
                }
            } catch (e: Exception) { }
            runOnUiThread {
                items = result
                selected.retainAll(result.map { it.id }.toSet())
                adapter.notifyDataSetChanged()
                updateButtons()
            }
        }.start()
    }

    private fun updateButtons() {
        title.text = "Enregistrements (" + items.size + ")"
        deleteBtn.text = "Supprimer (" + selected.size + ")"
        deleteBtn.isEnabled = selected.isNotEmpty()
        selectAll.text =
            if (items.isNotEmpty() && selected.size == items.size) "Tout désélectionner"
            else "Tout sélectionner"
    }

    // ---------- Suppression ----------

    private fun confirmDelete() {
        val n = selected.size
        if (n == 0) return
        AlertDialog.Builder(this)
            .setMessage("Supprimer $n vidéo(s) ? Cette action est définitive.")
            .setPositiveButton("Supprimer") { _, _ -> deleteSelected() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun deleteSelected() {
        val uris = items.filter { it.id in selected }.map { it.uri }
        Thread {
            val needsConsent = ArrayList<Uri>()
            for (u in uris) {
                try {
                    contentResolver.delete(u, null, null)
                } catch (e: SecurityException) {
                    needsConsent.add(u)
                } catch (e: Exception) { }
            }
            runOnUiThread {
                selected.clear()
                if (needsConsent.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
                    try {
                        val pi = MediaStore.createDeleteRequest(contentResolver, needsConsent)
                        deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                    } catch (e: Exception) {
                        Toast.makeText(this, "Suppression impossible", Toast.LENGTH_LONG).show()
                        load()
                    }
                } else load()
            }
        }.start()
    }

    private fun play(uri: Uri) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "video/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Aucun lecteur vidéo trouvé", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Liste ----------

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: LayoutInflater.from(this@RecordingsActivity)
                .inflate(R.layout.item_recording, parent, false)
            val h = (v.tag as? Holder) ?: Holder(
                v.findViewById(R.id.check), v.findViewById(R.id.thumb),
                v.findViewById(R.id.itemTitle), v.findViewById(R.id.itemSub)
            ).also { v.tag = it }

            val it = items[position]
            h.id = it.id
            h.check.isChecked = it.id in selected
            h.title.text = dateFmt.format(Date(it.dateMs))
            val sec = it.durMs / 1000
            h.sub.text = String.format(
                Locale.getDefault(), "Durée %d:%02d  ·  %.1f Mo",
                sec / 60, sec % 60, it.size / 1048576.0
            )
            h.thumb.setOnClickListener { _ -> play(it.uri) }

            val cached = thumbs.get(it.id)
            if (cached != null) {
                h.thumb.setImageBitmap(cached)
            } else {
                h.thumb.setImageDrawable(null)
                h.thumb.setBackgroundColor(0xFFDDDDDD.toInt())
                pool.execute {
                    try {
                        val bmp = contentResolver.loadThumbnail(it.uri, Size(192, 128), null)
                        thumbs.put(it.id, bmp)
                        runOnUiThread { if (h.id == it.id) h.thumb.setImageBitmap(bmp) }
                    } catch (e: Exception) { }
                }
            }
            return v
        }
    }
}
