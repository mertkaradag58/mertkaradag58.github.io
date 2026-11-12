package com.mertkaradag.stepviewer

import android.app.Application

/** Ayarları ve temayı Activity'ler oluşmadan önce uygular. */
class CepteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Units.load()
        Prefs.applyNightMode()
    }
}
