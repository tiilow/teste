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
        const val EXTRA_COUNTDOWN = "countdown"
        const val EXTRA_FLASH = "flash"
        private const val CHANNEL_ID = "surveillance"
        private const val NOTIF_ID = 1

        // Réglages (ajustez si trop / pas assez sensible)
        const val MAX_CLIP_MS = 30_000L      // durée max d'une vidéo
        const val COOLDOWN_MS = 3_000L       // pause entre deux vidéos
        const val MOTION_THRESHOLD = 12.0    // plus petit = plus sensible
        const val SOUND_THRESHOLD = 3000     // plus petit = plus sensible (max 32767)

        @Volatile var state: State = State.OFF
        @Volatile var secondsLeft: Int = 0
        var listener: (() -> Unit)? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var useFlash = true
    private var prev: ByteArray? = null
    private var armedAt = 0L
    private var cooldownUntil = 0L
    private var stopping = false
    @Volatile private var monitoring = false
    private var soundThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val stopClipRunnable = Runnable { recording?.stop() }
    private val tick = object : Runnable {
        override fun run() {
            secondsLeft--
            if (secondsLeft <= 0) arm() else {
                setState(State.COUNTDOWN)
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                goForeground()
                if (state == State.OFF) begin(intent)
            }
            ACTION_STOP -> requestShutdown()
        }
        return START_NOT_STICKY
    }

    // ---------- Cycle de vie de la surveillance ----------

    private fun begin(intent: Intent) {
        useFlash = intent.getBooleanExtra(EXTRA_FLASH, true)
        val secs = intent.getIntExtra(EXTRA_COUNTDOWN, 10).coerceIn(0, 3600)
        stopping = false
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "surveillance:wl")
            .apply { acquire() }
        secondsLeft = secs
        if (secs > 0) {
            setState(State.COUNTDOWN)
            handler.postDelayed(tick, 1000)
        } else arm()
    }

    private fun arm() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (stopping || state == State.OFF) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
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
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { analyze(it) }
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, vc, analysis
                )
                armedAt = SystemClock.elapsedRealtime()
                prev = null
                setState(State.ARMED)
                startSoundMonitor()
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
            val cur = ByteArray(cw * ch)
            var i = 0
            for (y in 0 until ch) {
                val rowStart = y * step * stride
                for (x in 0 until cw) cur[i++] = buf.get(rowStart + x * step)
            }
            val p = prev
            if (p != null && p.size == cur.size) {
                var sum = 0L
                for (k in cur.indices) {
                    sum += abs((cur[k].toInt() and 0xFF) - (p[k].toInt() and 0xFF))
                }
                if (sum.toDouble() / cur.size > MOTION_THRESHOLD) trigger()
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
                    if (peak > SOUND_THRESHOLD) trigger()
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
            val now = SystemClock.elapsedRealtime()
            if (state == State.ARMED && now - armedAt > 2000 && now > cooldownUntil) startClip()
        }
    }

    // ---------- Enregistrement ----------

    @SuppressLint("MissingPermission")
    private fun startClip() {
        val vc = videoCapture ?: return
        setState(State.RECORDING)
        stopSoundMonitor() // libère le micro pour la vidéo
        if (useFlash) camera?.cameraControl?.enableTorch(true)

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
        handler.postDelayed(stopClipRunnable, MAX_CLIP_MS)
    }

    private fun onClipFinalized() {
        recording = null
        handler.removeCallbacks(stopClipRunnable)
        camera?.cameraControl?.enableTorch(false)
        if (stopping) { finishShutdown(); return }
        val now = SystemClock.elapsedRealtime()
        cooldownUntil = now + COOLDOWN_MS
        armedAt = now
        prev = null
        setState(State.ARMED)
        startSoundMonitor()
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
        analysisExecutor.shutdown()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        state = State.OFF
    }
}
