package com.readall.app

import android.app.Application
import com.readall.app.data.AppContainer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class ReadAllApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        container = AppContainer(this)
    }
}
