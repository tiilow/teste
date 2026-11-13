package com.example.surveillance

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {
    private lateinit var s: Settings
    private lateinit var countdown: EditText
    private lateinit var clip: EditText
    private lateinit var cooldown: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        s = Settings(this)

        countdown = findViewById(R.id.countdown)
        clip = findViewById(R.id.clip)
        cooldown = findViewById(R.id.cooldown)
        countdown.setText(s.countdown.toString())
        clip.setText(s.clipSeconds.toString())
        cooldown.setText(s.cooldown.toString())

        bindSwitch(R.id.motionOn, s.motionOn) { s.motionOn = it }
        bindSwitch(R.id.soundOn, s.soundOn) { s.soundOn = it }
        bindSwitch(R.id.flash, s.flash) { s.flash = it }
        bindSeek(R.id.motionSeek, R.id.motionVal, s.motionSens) { s.motionSens = it }
        bindSeek(R.id.soundSeek, R.id.soundVal, s.soundSens) { s.soundSens = it }

        findViewById<Button>(R.id.battery).setOnClickListener { requestIgnoreBattery(this) }
        findViewById<Button>(R.id.done).setOnClickListener { finish() }
    }

    private fun bindSwitch(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        val sw = findViewById<Switch>(id)
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun bindSeek(seekId: Int, valueId: Int, initial: Int, onChange: (Int) -> Unit) {
        val sb = findViewById<SeekBar>(seekId)
        val tv = findViewById<TextView>(valueId)
        sb.max = 100
        sb.progress = initial
        tv.text = "$initial / 100"
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                tv.text = "$progress / 100"
                if (fromUser) onChange(progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
    }

    override fun onPause() {
        super.onPause()
        s.countdown = countdown.text.toString().toIntOrNull()?.coerceIn(0, 3600) ?: 10
        s.clipSeconds = clip.text.toString().toIntOrNull()?.coerceIn(5, 30) ?: 30
        s.cooldown = cooldown.text.toString().toIntOrNull()?.coerceIn(2, 120) ?: 5
    }
}
