package com.example.surveillance

import android.content.Context

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
}
