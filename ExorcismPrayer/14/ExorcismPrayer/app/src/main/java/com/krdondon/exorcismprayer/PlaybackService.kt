package com.krdondon.exorcismprayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Hosts playback outside the Activity so audio can continue in the background.
 *
 * Uses local-only [RawResourceDataSource] and [LocalArtworkBitmapLoader] so no network
 * HTTP data source or main-thread bitmap encoding is performed.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var bitmapLoader: LocalArtworkBitmapLoader? = null

    companion object {
        const val CHANNEL_ID = "playback_channel"
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.notification_channel_name)
            .build()
            .apply {
                setSmallIcon(R.drawable.ic_notification)
            }
        setMediaNotificationProvider(notificationProvider)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val localMediaSourceFactory = DefaultMediaSourceFactory(
            DataSource.Factory { RawResourceDataSource(this) }
        )

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(localMediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                setMediaItems(buildPlaylist())
                prepare()
            }

        val localBitmapLoader = LocalArtworkBitmapLoader(this)
        bitmapLoader = localBitmapLoader

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(sessionActivity)
            .setBitmapLoader(CacheBitmapLoader(localBitmapLoader))
            .setCallback(MediaSessionCallback())
            .build()
    }

    private fun buildPlaylist(): List<MediaItem> {
        val appName = getString(R.string.app_name)
        val artworkUri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(packageName)
            .appendPath(R.mipmap.ic_launcher.toString())
            .build()

        return mediaList.map { item ->
            val mediaUri = Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(packageName)
                .appendPath(item.resourceId.toString())
                .build()

            MediaItem.Builder()
                .setMediaId(item.resourceId.toString())
                .setUri(mediaUri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtist(appName)
                        .setDisplayTitle(item.title)
                        .setSubtitle(appName)
                        .setDescription(item.description)
                        .setArtworkUri(artworkUri)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .setIsPlayable(true)
                        .build()
                )
                .build()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.createNotificationChannel(channel)
    }

    private class MediaSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().build()
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .add(Player.COMMAND_PLAY_PAUSE)
                .add(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
                .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
                .add(Player.COMMAND_STOP)
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null

        player?.release()
        player = null

        bitmapLoader?.shutdown()
        bitmapLoader = null

        super.onDestroy()
    }
}
