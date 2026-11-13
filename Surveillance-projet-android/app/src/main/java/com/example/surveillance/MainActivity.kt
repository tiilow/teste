package com.example.surveillance

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
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
    private lateinit var previewView: PreviewView
    private lateinit var settings: Settings
    private val handler = Handler(Looper.getMainLooper())
    private var localBound = false
    private var askedPerms = false

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
                        loc, "Mouvement : %.1f %%  (seuil %.1f %%)\n",
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
        previewView = findViewById(R.id.previewView)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

        findViewById<ImageButton>(R.id.gear).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

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
