package com.example.surveillance

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var levels: TextView
    private lateinit var toggle: Button
    private lateinit var zoneBtn: Button
    private lateinit var previewFrame: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var overlay: ZoneOverlay
    private lateinit var settings: Settings
    private val handler = Handler(Looper.getMainLooper())
    private var localBound = false
    private var askedPerms = false
    private var editingZone = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasCamMic()) refresh() else status.text = "Autorisations caméra et micro requises"
        }

    private val levelsRunnable = object : Runnable {
        override fun run() {
            val sb = StringBuilder()
            if (SurveillanceService.state != State.OFF) {
                val loc = Locale.getDefault()
                if (settings.motionOn) sb.append(
                    String.format(
                        loc, "Mouvement (dans la zone) : %.1f %%  (seuil %.1f %%)\n",
                        SurveillanceService.motionPct, SurveillanceService.motionThr
                    )
                )
                if (settings.soundOn) sb.append(
                    String.format(
                        loc, "Son : %.0f %%  (seuil %.0f %%)",
                        SurveillanceService.soundPct, SurveillanceService.soundThr
                    )
                )
            }
            levels.text = sb.toString()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        status = findViewById(R.id.status)
        levels = findViewById(R.id.levels)
        toggle = findViewById(R.id.toggle)
        zoneBtn = findViewById(R.id.zoneBtn)
        previewFrame = findViewById(R.id.previewFrame)
        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.zoneOverlay)
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        // L'aperçu et la zone occupent exactement le même rectangle (format portrait 9:16)
        previewFrame.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
            val aspect = 9f / 16f
            var vw = w
            var vh = (w / aspect).toInt()
            if (vh > h) { vh = h; vw = (h * aspect).toInt() }
            if (previewView.layoutParams.width != vw || previewView.layoutParams.height != vh) {
                previewView.layoutParams = FrameLayout.LayoutParams(vw, vh, Gravity.CENTER)
                overlay.layoutParams = FrameLayout.LayoutParams(vw, vh, Gravity.CENTER)
            }
        }

        findViewById<ImageButton>(R.id.gear).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.recordingsBtn).setOnClickListener {
            startActivity(Intent(this, RecordingsActivity::class.java))
        }
        zoneBtn.setOnClickListener { onZoneButton() }

        toggle.setOnClickListener {
            if (SurveillanceService.state == State.OFF) {
                if (hasCamMic()) startSurveillance() else permLauncher.launch(needed())
            } else {
                startService(
                    Intent(this, SurveillanceService::class.java)
                        .setAction(SurveillanceService.ACTION_STOP)
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SurveillanceService.listener = { refresh() }
        SurveillanceService.setPreviewSurface(previewView.surfaceProvider)
        if (!editingZone) {
            overlay.setZone(settings.zoneL, settings.zoneT, settings.zoneR, settings.zoneB)
        }
        if (!hasCamMic() && !askedPerms) {
            askedPerms = true
            permLauncher.launch(needed())
        } else refresh()
        handler.post(levelsRunnable)
    }

    override fun onPause() {
        super.onPause()
        SurveillanceService.listener = null
        SurveillanceService.setPreviewSurface(null)
        handler.removeCallbacks(levelsRunnable)
    }

    // ---------- Zone de détection ----------

    private fun onZoneButton() {
        if (editingZone) {
            val z = overlay.zone
            if (z.width() < 0.1f || z.height() < 0.1f) {
                Toast.makeText(this, "Zone trop petite, redessine-la", Toast.LENGTH_SHORT).show()
                return
            }
            settings.setZone(z.left, z.top, z.right, z.bottom)
            editingZone = false
            overlay.editing = false
            overlay.invalidate()
            zoneBtn.text = "Zone de détection"
        } else {
            AlertDialog.Builder(this)
                .setTitle("Zone de détection")
                .setItems(arrayOf("Dessiner une zone", "Zone entière (tout l'écran)")) { _, which ->
                    if (which == 0) {
                        editingZone = true
                        overlay.editing = true
                        zoneBtn.text = "Valider la zone"
                        overlay.invalidate()
                        Toast.makeText(
                            this,
                            "Dessine un rectangle sur l'image avec le doigt, puis appuie sur « Valider la zone ».",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        settings.setZone(0f, 0f, 1f, 1f)
                        overlay.setZone(0f, 0f, 1f, 1f)
                    }
                }
                .show()
        }
    }

    // ---------- Batterie ----------

    private fun maybeAskBattery() {
        if (settings.batteryAsked) return
        settings.batteryAsked = true
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        AlertDialog.Builder(this)
            .setTitle("Rester actif la nuit")
            .setMessage(
                "Pour que Samsung ne coupe pas la surveillance, autorise l'appli à ignorer " +
                    "l'optimisation de la batterie. Une fenêtre Android va s'ouvrir : choisis « Autoriser »."
            )
            .setPositiveButton("Continuer") { _, _ -> requestIgnoreBattery(this) }
            .setNegativeButton("Plus tard", null)
            .show()
    }

    // ---------- Permissions / démarrage ----------

    private fun needed(): Array<String> {
        val l = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l.toTypedArray()
    }

    private fun hasCamMic() = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun startSurveillance() {
        localBound = false
        startForegroundService(
            Intent(this, SurveillanceService::class.java)
                .setAction(SurveillanceService.ACTION_START)
        )
    }

    private fun refresh() {
        updateUi()
        if (SurveillanceService.state == State.OFF) {
            if (!localBound && hasCamMic()) bindLocalPreview()
            if (hasCamMic()) maybeAskBattery()
        } else {
            localBound = false
        }
    }

    // Aperçu de la caméra quand la surveillance est éteinte
    private fun bindLocalPreview() {
        localBound = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (SurveillanceService.state != State.OFF) { localBound = false; return@addListener }
            try {
                val provider = future.get()
                val ratio = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .build()
                val preview = Preview.Builder().setResolutionSelector(ratio).build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (e: Exception) {
                localBound = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateUi() {
        when (SurveillanceService.state) {
            State.OFF -> { status.text = "Surveillance désactivée"; toggle.text = "ACTIVER" }
            State.COUNTDOWN -> {
                status.text = "Activation dans ${SurveillanceService.secondsLeft} s"
                toggle.text = "ARRÊTER"
            }
            State.ARMED -> { status.text = "Surveillance ACTIVE"; toggle.text = "ARRÊTER" }
            State.RECORDING -> { status.text = "● Enregistrement en cours"; toggle.text = "ARRÊTER" }
        }
    }
}
