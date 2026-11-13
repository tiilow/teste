package com.example.surveillance

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var flash: Switch
    private lateinit var countdown: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)
        flash = findViewById(R.id.flash)
        countdown = findViewById(R.id.countdown)

        toggle.setOnClickListener {
            if (SurveillanceService.state == State.OFF) {
                if (hasPermissions()) startSurveillance() else requestPerms()
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
        SurveillanceService.listener = { updateUi() }
        updateUi()
    }

    override fun onPause() {
        super.onPause()
        SurveillanceService.listener = null
    }

    private fun needed(): Array<String> {
        val l = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l.toTypedArray()
    }

    private fun hasPermissions() = needed().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPerms() = requestPermissions(needed(), 1)

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        val camMic = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val ok = camMic.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (ok) startSurveillance() else status.text = "Autorisations caméra et micro requises"
    }

    private fun startSurveillance() {
        val secs = countdown.text.toString().toIntOrNull() ?: 10
        val i = Intent(this, SurveillanceService::class.java)
            .setAction(SurveillanceService.ACTION_START)
            .putExtra(SurveillanceService.EXTRA_COUNTDOWN, secs)
            .putExtra(SurveillanceService.EXTRA_FLASH, flash.isChecked)
        startForegroundService(i)
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
        val off = SurveillanceService.state == State.OFF
        countdown.isEnabled = off
        flash.isEnabled = off
    }
}
