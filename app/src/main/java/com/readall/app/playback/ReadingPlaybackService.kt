package com.readall.app.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.readall.app.MainActivity
import com.readall.app.R
import com.readall.app.ReadAllApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ReadingPlaybackService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mediaSession: MediaSessionCompat
    private var observeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val playback: PlaybackController
        get() = (application as ReadAllApplication).container.playback

    override fun onCreate() {
        super.onCreate()
        createChannel()
        mediaSession = MediaSessionCompat(this, "ReadAll").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = playback.play()
                override fun onPause() = playback.pause()
                override fun onSkipToNext() = playback.nextPage()
                override fun onSkipToPrevious() = playback.previousPage()
            })
            isActive = true
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(playback.state.value),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        )
        observeJob = serviceScope.launch {
            playback.state.collectLatest { state ->
                updatePlaybackState(state)
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(state))
                updateWakeLock(state.isPlaying)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> playback.play()
            ACTION_PAUSE -> playback.pause()
            ACTION_TOGGLE -> playback.togglePlay()
            ACTION_PREV -> playback.previousPage()
            ACTION_NEXT -> playback.nextPage()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observeJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    private fun updatePlaybackState(state: PlaybackState) {
        val sessionState = if (state.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(sessionState, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.title.ifBlank { "ReadAll" })
                .putString(
                    MediaMetadataCompat.METADATA_KEY_ARTIST,
                    "${state.currentPage + 1} / ${state.pageCount.coerceAtLeast(1)}페이지"
                )
                .build()
        )
    }

    private fun buildNotification(state: PlaybackState): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_DOCUMENT_ID, state.documentId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIcon = if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseAction = if (state.isPlaying) ACTION_PAUSE else ACTION_PLAY
        val playPauseLabel = if (state.isPlaying) "일시정지" else "재생"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_read)
            .setContentTitle(state.title.ifBlank { "ReadAll" })
            .setContentText("${state.currentPage + 1} / ${state.pageCount.coerceAtLeast(1)}페이지")
            .setContentIntent(openApp)
            .setOngoing(state.isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(android.R.drawable.ic_media_previous, "이전 페이지", servicePendingIntent(ACTION_PREV, 1))
            .addAction(playPauseIcon, playPauseLabel, servicePendingIntent(playPauseAction, 2))
            .addAction(android.R.drawable.ic_media_next, "다음 페이지", servicePendingIntent(ACTION_NEXT, 3))
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            this,
            requestCode,
            Intent(this, ReadingPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "문서 읽기",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "PDF 음성 재생 알림"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun updateWakeLock(playing: Boolean) {
        if (playing) {
            if (wakeLock?.isHeld == true) return
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReadAll:reading")
                .apply {
                    setReferenceCounted(false)
                    acquire(12 * 60 * 60 * 1000L)
                }
        } else {
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.readall.app.action.START"
        const val ACTION_PLAY = "com.readall.app.action.PLAY"
        const val ACTION_PAUSE = "com.readall.app.action.PAUSE"
        const val ACTION_TOGGLE = "com.readall.app.action.TOGGLE"
        const val ACTION_PREV = "com.readall.app.action.PREV"
        const val ACTION_NEXT = "com.readall.app.action.NEXT"
        const val EXTRA_DOCUMENT_ID = "documentId"
        private const val CHANNEL_ID = "readall_playback"
        private const val NOTIFICATION_ID = 1001
    }
}
