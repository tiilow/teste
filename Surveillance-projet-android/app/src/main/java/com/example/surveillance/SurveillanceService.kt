package com.example.surveillance

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

enum class State { OFF, COUNTDOWN, ARMED, RECORDING }

class SurveillanceService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.example.surveillance.START"
        const val ACTION_STOP = "com.example.surveillance.STOP"
        private const val CHANNEL_ID = "surveillance"
        private const val NOTIF_ID = 1

        @Volatile var state: State = State.OFF
        @Volatile var secondsLeft: Int = 0
        @Volatile var motionPct = 0.0
        @Volatile var motionThr = 0.0
        @Volatile var soundPct = 0.0
        @Volatile var soundThr = 0.0
        var listener: (() -> Unit)? = null

        private var previewUseCase: Preview? = null
        private var previewSurface: Preview.SurfaceProvider? = null

        /** Appelé par l'écran principal (thread principal) pour afficher l'aperçu. */
        fun setPreviewSurface(p: Preview.SurfaceProvider?) {
            previewSurface = p
            previewUseCase?.setSurfaceProvider(p)
        }
    }

    private lateinit var settings: Settings
    private val handler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    @Volatile private var prev: ByteArray? = null
    private var lastZoneKey = 0
    private var ignoreUntil = 0L
    private var stopping = false
    @Volatile private var monitoring = false
    private var soundThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val stopClipRunnable = Runnable { recording?.stop() }
    private val tick = object : Runnable {
        override fun run() {
            secondsLeft--
            if (secondsLeft <= 0) becomeArmed() else {
                setState(State.COUNTDOWN)
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                goForeground()
                if (state == State.OFF) begin()
            }
            ACTION_STOP -> requestShutdown()
        }
        return START_NOT_STICKY
    }

    // ---------- Cycle de vie ----------

    private fun begin() {
        stopping = false
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "surveillance:wl")
            .apply { acquire() }
        val secs = settings.countdown.coerceIn(0, 3600)
        secondsLeft = secs
        motionPct = 0.0; soundPct = 0.0
        setState(State.COUNTDOWN)
        bindCamera()                       // aperçu visible dès le compte à rebours
        if (settings.soundOn) startSoundMonitor()
        if (secs > 0) handler.postDelayed(tick, 1000) else becomeArmed()
    }

    private fun becomeArmed() {
        ignoreUntil = SystemClock.elapsedRealtime() + 2000
        prev = null
        setState(State.ARMED)
        if (settings.soundOn) startSoundMonitor()
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (stopping || state == State.OFF) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                val ratio = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .build()
                val preview = Preview.Builder().setResolutionSelector(ratio).build()
                preview.setSurfaceProvider(previewSurface)
                previewUseCase = preview

                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.HD,
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                        )
                    ).build()
                val vc = VideoCapture.withOutput(recorder)
                videoCapture = vc

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(ratio)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { analyze(it) }

                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, vc, analysis
                )
                // Flash allumé en permanence pendant toute la surveillance
                if (settings.flash) camera?.cameraControl?.enableTorch(true)
                ignoreUntil = SystemClock.elapsedRealtime() + 3000
                prev = null
            } catch (e: Exception) {
                requestShutdown()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun requestShutdown() {
        if (stopping) return
        stopping = true
        handler.removeCallbacks(tick)
        handler.removeCallbacks(stopClipRunnable)
        val r = recording
        if (r != null) r.stop() else finishShutdown()
    }

    private fun finishShutdown() {
        stopSoundMonitor()
        camera?.cameraControl?.enableTorch(false)
        cameraProvider?.unbindAll()
        previewUseCase = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        state = State.OFF
        secondsLeft = 0
        listener?.invoke()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------- Détection ----------

    private fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val stride = plane.rowStride
            val step = 8
            val cw = image.width / step
            val ch = image.height / step

            // Zone choisie (image à l'endroit) -> repère du buffer de la caméra
            val zl = settings.zoneL
            val zt = settings.zoneT
            val zr = settings.zoneR
            val zb = settings.zoneB
            val z = when (image.imageInfo.rotationDegrees) {
                90 -> floatArrayOf(zt, zb, 1f - zr, 1f - zl)
                180 -> floatArrayOf(1f - zr, 1f - zl, 1f - zb, 1f - zt)
                270 -> floatArrayOf(1f - zb, 1f - zt, zl, zr)
                else -> floatArrayOf(zl, zr, zt, zb)
            }
            val cx0 = (z[0] * cw).toInt().coerceIn(0, cw - 1)
            val cx1 = Math.ceil((z[1] * cw).toDouble()).toInt().coerceIn(cx0 + 1, cw)
            val cy0 = (z[2] * ch).toInt().coerceIn(0, ch - 1)
            val cy1 = Math.ceil((z[3] * ch).toDouble()).toInt().coerceIn(cy0 + 1, ch)

            val key = z.contentHashCode()
            if (key != lastZoneKey) { lastZoneKey = key; prev = null }

            val cur = ByteArray(cw * ch)
            for (y in cy0 until cy1) {
                val rowStart = y * step * stride
                for (x in cx0 until cx1) cur[y * cw + x] = buf.get(rowStart + x * step)
            }

            val thr = 0.3 + (100 - settings.motionSens) * 0.12   // % de pixels de la zone qui changent
            motionThr = thr
            val p = prev
            if (p != null && p.size == cur.size) {
                var changed = 0
                for (y in cy0 until cy1) {
                    for (x in cx0 until cx1) {
                        val k = y * cw + x
                        if (abs((cur[k].toInt() and 0xFF) - (p[k].toInt() and 0xFF)) > 25) changed++
                    }
                }
                val pct = 100.0 * changed / ((cx1 - cx0) * (cy1 - cy0))
                motionPct = max(pct, motionPct * 0.9)
                if (settings.motionOn && pct > thr) trigger()
            }
            prev = cur
        } finally {
            image.close()
        }
    }

    private fun startSoundMonitor() {
        if (soundThread != null) return
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        @SuppressLint("MissingPermission")
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(min, 4096)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
        monitoring = true
        soundThread = thread {
            rec.startRecording()
            val buf = ShortArray(1024)
            while (monitoring) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    var peak = 0
                    for (k in 0 until n) peak = max(peak, abs(buf[k].toInt()))
                    val pct = peak * 100.0 / 32767
                    val thr = 1.0 + (100 - settings.soundSens) * 0.3   // % du volume max
                    soundThr = thr
                    soundPct = max(pct, soundPct * 0.85)
                    if (settings.soundOn && pct > thr) trigger()
                }
            }
            rec.stop()
            rec.release()
        }
    }

    private fun stopSoundMonitor() {
        monitoring = false
        soundThread?.join(800)
        soundThread = null
    }

    private fun trigger() {
        handler.post {
            if (state == State.ARMED && SystemClock.elapsedRealtime() > ignoreUntil) startClip()
        }
    }

    // ---------- Enregistrement (uniquement sur détection) ----------

    @SuppressLint("MissingPermission")
    private fun startClip() {
        val vc = videoCapture ?: return
        setState(State.RECORDING)
        stopSoundMonitor() // libère le micro pour la vidéo

        val name = "surv_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Surveillance")
        }
        val opts = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        recording = vc.output.prepareRecording(this, opts)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { ev ->
                if (ev is VideoRecordEvent.Finalize) onClipFinalized()
            }
        handler.postDelayed(stopClipRunnable, settings.clipSeconds.coerceIn(5, 30) * 1000L)
    }

    private fun onClipFinalized() {
        recording = null
        handler.removeCallbacks(stopClipRunnable)
        if (stopping) { finishShutdown(); return }
        ignoreUntil = SystemClock.elapsedRealtime() + max(settings.cooldown, 2) * 1000L
        prev = null
        setState(State.ARMED)
        if (settings.soundOn) startSoundMonitor()
    }

    // ---------- Notification / état ----------

    private fun setState(s: State) {
        state = s
        listener?.invoke()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification())
    }

    private fun goForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Surveillance", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(
            NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
    }

    private fun buildNotification(): Notification {
        val text = when (state) {
            State.COUNTDOWN -> "Activation dans $secondsLeft s"
            State.ARMED -> "Surveillance active"
            State.RECORDING -> "Enregistrement en cours"
            State.OFF -> "Démarrage…"
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Surveillance")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        monitoring = false
        previewUseCase = null
        analysisExecutor.shutdown()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        state = State.OFF
    }
}
