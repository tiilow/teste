package com.example.surveillance

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri

class Settings(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    var countdown: Int
        get() = sp.getInt("countdown", 10)
        set(v) { sp.edit().putInt("countdown", v).apply() }
    var clipSeconds: Int
        get() = sp.getInt("clipSeconds", 30)
        set(v) { sp.edit().putInt("clipSeconds", v).apply() }
    var cooldown: Int
        get() = sp.getInt("cooldown", 5)
        set(v) { sp.edit().putInt("cooldown", v).apply() }
    var motionOn: Boolean
        get() = sp.getBoolean("motionOn", true)
        set(v) { sp.edit().putBoolean("motionOn", v).apply() }
    var motionSens: Int
        get() = sp.getInt("motionSens", 70)
        set(v) { sp.edit().putInt("motionSens", v).apply() }
    var soundOn: Boolean
        get() = sp.getBoolean("soundOn", true)
        set(v) { sp.edit().putBoolean("soundOn", v).apply() }
    var soundSens: Int
        get() = sp.getInt("soundSens", 70)
        set(v) { sp.edit().putInt("soundSens", v).apply() }
    var flash: Boolean
        get() = sp.getBoolean("flash", true)
        set(v) { sp.edit().putBoolean("flash", v).apply() }
    var batteryAsked: Boolean
        get() = sp.getBoolean("batteryAsked", false)
        set(v) { sp.edit().putBoolean("batteryAsked", v).apply() }

    // Zone de détection, en proportions de l'image (0 à 1)
    val zoneL: Float get() = sp.getFloat("zl", 0f)
    val zoneT: Float get() = sp.getFloat("zt", 0f)
    val zoneR: Float get() = sp.getFloat("zr", 1f)
    val zoneB: Float get() = sp.getFloat("zb", 1f)
    fun setZone(l: Float, t: Float, r: Float, b: Float) {
        sp.edit().putFloat("zl", l).putFloat("zt", t).putFloat("zr", r).putFloat("zb", b).apply()
    }
}

fun requestIgnoreBattery(a: Activity) {
    try {
        a.startActivity(
            Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + a.packageName)
            )
        )
    } catch (e: Exception) {
        try {
            a.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e2: Exception) { }
    }
}
