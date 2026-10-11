package com.castbrowser.app.cast

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import com.castbrowser.app.CastApplication
import com.castbrowser.app.MainActivity
import com.castbrowser.app.data.AppRepository
import com.castbrowser.app.data.EpisodeTitleParser
import com.castbrowser.app.proxy.LocalStreamProxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class CastForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var mediaSession: MediaSessionCompat? = null
    private var proxyServer: LocalStreamProxy? = null
    private var castController: CastController? = null
    private var activeMedia: CastMedia? = null
    private var activePageUrl: String? = null

    companion object {
        const val CHANNEL_ID = "cast_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "com.castbrowser.app.ACTION_PLAY"
        const val ACTION_PAUSE = "com.castbrowser.app.ACTION_PAUSE"
        const val ACTION_STOP = "com.castbrowser.app.ACTION_STOP"
        const val ACTION_SEEK_FORWARD = "com.castbrowser.app.ACTION_SEEK_FORWARD"
        const val ACTION_SEEK_REWIND = "com.castbrowser.app.ACTION_SEEK_REWIND"

        @Volatile
        var activeControllerInstance: CastController? = null
            private set

        @Volatile
        var activeProxyInstance: LocalStreamProxy? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> serviceScope.launch { castController?.play() }
            ACTION_PAUSE -> serviceScope.launch { castController?.pause() }
            ACTION_STOP -> {
                serviceScope.launch {
                    castController?.stop()
                    stopSelf()
                }
            }
            ACTION_SEEK_FORWARD -> {
                serviceScope.launch {
                    val current = castController?.status?.value?.positionMs ?: 0L
                    castController?.seekTo(current + 10_000L)
                }
            }
            ACTION_SEEK_REWIND -> {
                serviceScope.launch {
                    val current = castController?.status?.value?.positionMs ?: 0L
                    castController?.seekTo((current - 10_000L).coerceAtLeast(0L))
                }
            }
        }
        return START_NOT_STICKY
    }

    fun startCasting(
        controller: CastController,
        media: CastMedia,
        pageUrl: String,
        proxy: LocalStreamProxy?,
        startMs: Long,
        repository: AppRepository
    ) {
        castController = controller
        activeControllerInstance = controller
        proxyServer = proxy
        activeProxyInstance = proxy
        activeMedia = media
        activePageUrl = pageUrl

        val notification = buildNotification("Connecting to TV...", media.title, PlaybackState.BUFFERING)
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            controller.load(media, startMs)

            // Collect playback status and update notification & database
            controller.status.collectLatest { status ->
                updateNotification(media.title, status)
                updateMediaSessionState(status)

                // Persist watch progress to database
                if (status.durationMs > 0 && activePageUrl != null) {
                    val host = try { java.net.URI(pageUrl).host ?: "" } catch (_: Exception) { "" }
                    val parsed = EpisodeTitleParser.parse(media.title, pageUrl)

                    repository.recordOrUpdateWatch(
                        pageUrl = pageUrl,
                        title = parsed.title,
                        host = host,
                        seriesKey = parsed.seriesKey,
                        season = parsed.season,
                        episode = parsed.episode,
                        positionMs = status.positionMs,
                        durationMs = status.durationMs
                    )
                }
            }
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "CastMediaSession").apply {
            isActive = true
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    serviceScope.launch { castController?.play() }
                }

                override fun onPause() {
                    serviceScope.launch { castController?.pause() }
                }

                override fun onStop() {
                    serviceScope.launch {
                        castController?.stop()
                        stopSelf()
                    }
                }

                override fun onSeekTo(pos: Long) {
                    serviceScope.launch { castController?.seekTo(pos) }
                }
            })
        }
    }

    private fun updateMediaSessionState(status: PlaybackStatus) {
        val state = when (status.state) {
            PlaybackState.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            PlaybackState.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            PlaybackState.BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            PlaybackState.STOPPED -> PlaybackStateCompat.STATE_STOPPED
            else -> PlaybackStateCompat.STATE_NONE
        }

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, status.positionMs, 1.0f)
            .build()

        mediaSession?.setPlaybackState(playbackState)
    }

    private fun updateNotification(title: String, status: PlaybackStatus) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = buildNotification(
            subtitle = if (status.state == PlaybackState.PLAYING) "Playing on TV" else "Paused",
            title = title,
            state = status.state
        )
        notificationManager.notify(NOTIFICATION_ID, notif)
    }

    private fun buildNotification(subtitle: String, title: String, state: PlaybackState): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (state == PlaybackState.PLAYING) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "Pause",
                getServicePendingIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "Play",
                getServicePendingIntent(ACTION_PLAY)
            )
        }

        val stopAction = NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Stop",
            getServicePendingIntent(ACTION_STOP)
        )

        val seekBackAction = NotificationCompat.Action(
            android.R.drawable.ic_media_rew,
            "-10s",
            getServicePendingIntent(ACTION_SEEK_REWIND)
        )

        val seekFwdAction = NotificationCompat.Action(
            android.R.drawable.ic_media_ff,
            "+10s",
            getServicePendingIntent(ACTION_SEEK_FORWARD)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .addAction(seekBackAction)
            .addAction(playPauseAction)
            .addAction(seekFwdAction)
            .addAction(stopAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(1, 3)
            )
            .setOngoing(state == PlaybackState.PLAYING)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun getServicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, CastForegroundService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TV Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active casting controls on TV"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        mediaSession?.release()
        activeControllerInstance?.release()
        activeControllerInstance = null
        try {
            proxyServer?.stop()
        } catch (_: Exception) {}
        activeProxyInstance = null
    }
}
