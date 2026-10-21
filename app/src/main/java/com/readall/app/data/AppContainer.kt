package com.readall.app.data

import android.app.Application
import com.readall.app.playback.PlaybackController

class AppContainer(application: Application) {
    val repository: DocumentRepository = DocumentRepository(application)
    val playback: PlaybackController = PlaybackController(application, repository)
}
