package com.krdondon.exorcismprayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.AssetDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Hosts playback outside the Activity so audio can continue in the background.
 *
 * Uses local-only [AssetDataSource] and [LocalArtworkBitmapLoader] so no network
 * HTTP data source or main-thread bitmap encoding is performed.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var bitmapLoader: LocalArtworkBitmapLoader? = null
    private var currentVoice: PrayerVoice = PrayerVoice.DEFAULT

    companion object {
        const val CHANNEL_ID = "playback_channel"
        const val PREFS_NAME = "exorcism_prayer_prefs"
        const val KEY_PRAYER_VOICE = "key_prayer_voice"
        const val ACTION_SET_VOICE = "com.krdondon.exorcismprayer.ACTION_SET_VOICE"
        const val EXTRA_VOICE = "extra_voice"
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        currentVoice = PrayerVoice.fromStorageKey(prefs.getString(KEY_PRAYER_VOICE, null))

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
            DataSource.Factory { AssetDataSource(this) }
        )

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(localMediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                setMediaItems(buildPlaylist(currentVoice))
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
        val sessionPlayer = LoopingNavigationPlayer(exoPlayer)
        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(sessionActivity)
            .setBitmapLoader(CacheBitmapLoader(localBitmapLoader))
            .setSessionExtras(Bundle().apply { putString(EXTRA_VOICE, currentVoice.storageKey) })
            .setCallback(MediaSessionCallback())
            .build()
    }

    private fun switchVoice(newVoice: PrayerVoice) {
        if (newVoice == currentVoice) return
        currentVoice = newVoice

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putString(KEY_PRAYER_VOICE, newVoice.storageKey)
        }

        val exoPlayer = player
        if (exoPlayer != null) {
            val currentIndex = exoPlayer.currentMediaItemIndex.coerceIn(0, mediaList.lastIndex)
            val wasPlaying = exoPlayer.playWhenReady || exoPlayer.isPlaying
            exoPlayer.setMediaItems(buildPlaylist(newVoice), currentIndex, 0L)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = wasPlaying
        }

        mediaSession?.setSessionExtras(
            Bundle().apply { putString(EXTRA_VOICE, newVoice.storageKey) }
        )
    }

    private fun buildPlaylist(voice: PrayerVoice): List<MediaItem> {
        val appName = getString(R.string.app_name)
        val artworkUri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(packageName)
            .appendPath(R.mipmap.ic_launcher.toString())
            .build()

        return mediaList.map { item ->
            val assetPath = item.assetPathFor(voice)
            val mediaUri = "asset:///$assetPath".toUri()

            MediaItem.Builder()
                .setMediaId("${voice.storageKey}:$assetPath")
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

    private inner class MediaSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_SET_VOICE, Bundle.EMPTY))
                .build()
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

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_SET_VOICE) {
                val targetVoice = PrayerVoice.fromStorageKey(args.getString(EXTRA_VOICE))
                switchVoice(targetVoice)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
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

    private class LoopingNavigationPlayer(
        exoPlayer: ExoPlayer
    ) : ForwardingSimpleBasePlayer(exoPlayer) {

        override fun getState(): State {
            val baseState = super.getState()
            if (baseState.playlist.size <= 1) return baseState
            val commands = baseState.availableCommands.buildUpon()
                .add(COMMAND_SEEK_TO_PREVIOUS)
                .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(COMMAND_SEEK_TO_NEXT)
                .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .build()
            return baseState.buildUpon()
                .setAvailableCommands(commands)
                .build()
        }

        override fun handleSeek(
            mediaItemIndex: Int,
            positionMs: Long,
            seekCommand: Int
        ): ListenableFuture<*> {
            val itemCount = player.mediaItemCount
            if (itemCount > 1) {
                if (seekCommand == COMMAND_SEEK_TO_PREVIOUS ||
                    seekCommand == COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
                ) {
                    val targetIndex = previousMediaIndex(player.currentMediaItemIndex, itemCount)
                    return super.handleSeek(targetIndex, C.TIME_UNSET, COMMAND_SEEK_TO_MEDIA_ITEM)
                } else if (seekCommand == COMMAND_SEEK_TO_NEXT ||
                    seekCommand == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
                ) {
                    val targetIndex = nextMediaIndex(player.currentMediaItemIndex, itemCount)
                    return super.handleSeek(targetIndex, C.TIME_UNSET, COMMAND_SEEK_TO_MEDIA_ITEM)
                }
            }
            return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
    }
}
