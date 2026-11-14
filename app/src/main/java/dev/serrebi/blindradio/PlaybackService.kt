package dev.serrebi.blindradio

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the process and the network awake while a station plays, and gives the
 * system a media session: remote and headset play/pause, the TV's now-playing card.
 * The engine itself lives in [Engine]; this only follows it.
 */
class PlaybackService : Service() {
    companion object {
        var instance: PlaybackService? = null
            private set
        private const val CHANNEL = "playback"
        private const val ID = 1
        private const val ACTION_STOP = "dev.serrebi.blindradio.STOP"
    }

    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private var focus: AudioFocusRequest? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    /** Lost to a call or another app for a moment: play again when it gives focus back. */
    private var resume = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        audio = getSystemService(AudioManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel), NotificationManager.IMPORTANCE_LOW),
        )
        session = MediaSession(this, "BlindRadio").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { if (Engine.playing == null) Engine.toggle() }
                override fun onPause() { Engine.stop() }
                override fun onStop() { Engine.stop() }
            })
            isActive = true
        }
        // Held as long as a station plays, however long that is; released in onDestroy.
        @SuppressLint("WakelockTimeout")
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BlindRadio:play").apply { acquire() }
        wifi = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "BlindRadio:play").apply { acquire() }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> { resume = false; Engine.stop() }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        resume = Engine.playing != null
                        Engine.stop()
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> if (resume) { resume = false; Engine.toggle() }
                }
            }
            .build()
        focus = request
        audio.requestAudioFocus(request)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Engine.stop()
        }
        startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        update()
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(Engine.playing?.optString("name") ?: getString(R.string.app_name))
            .setContentText(Engine.line)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
            .build()
    }

    /** Follows the engine: new station or title, or stopped (then the service ends). */
    fun update() {
        val st = Engine.playing
        if (st == null && !resume) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP,
                )
                .setState(
                    if (st != null) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f,
                )
                .build(),
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, st?.optString("name") ?: "")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, Engine.line)
                .build(),
        )
        getSystemService(NotificationManager::class.java).notify(ID, notification())
    }

    override fun onDestroy() {
        instance = null
        focus?.let { audio.abandonAudioFocusRequest(it) }
        session.release()
        wake?.takeIf { it.isHeld }?.release()
        wifi?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}
