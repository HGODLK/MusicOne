package com.musicone.demo

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal enum class SystemPlaybackCommand { PREVIOUS, NEXT }

internal object SystemPlaybackCommands {
    private val mutableCommands = MutableSharedFlow<SystemPlaybackCommand>(extraBufferCapacity = 8)
    val commands = mutableCommands.asSharedFlow()

    fun send(command: SystemPlaybackCommand) {
        mutableCommands.tryEmit(command)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null
    private var usbAudioCoordinator: UsbAudioServiceCoordinator? = null
    private var detailsLoader: PlaybackDetailsLoader? = null
    private var routingProvider: RoutingAudioTrackProvider? = null
    private val timerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            if (PlaybackSleepTimer.expired()) { exoPlayer?.pause(); PlaybackSleepTimer.set(0) }
            timerHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        PlaybackOptions.initialize(this)
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(PlatformHttp.DEFAULT_USER_AGENT)
        val mediaSourceFactory = DefaultMediaSourceFactory(
            MusicDiskCache.get(this).factory(DefaultDataSource.Factory(this, httpDataSourceFactory)),
        )
        val renderersFactory = HiResCompatibleRenderersFactory(this)
        routingProvider = renderersFactory.routingProvider
        val player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            // 在线音源息屏后仍需维持网络接收，避免 USB 写入端因上游断流而停顿。
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
        }
        exoPlayer = player
        detailsLoader = PlaybackDetailsLoader(this, player).also(player::addListener)
        usbAudioCoordinator = renderersFactory.usbAudioSink?.let { sink ->
            UsbAudioServiceCoordinator(this, player, sink).also { it.start() }
        }
        timerHandler.post(timerTick)
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { PlaybackAudioInfo.clear() }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val url = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
                tracks.groups.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && it.isSelected }?.let { group ->
                    val index = (0 until group.length).firstOrNull(group::isTrackSelected) ?: return
                    val format = group.getTrackFormat(index)
                    PlaybackAudioInfo.publish(url, format.sampleMimeType, format.sampleRate, format.bitrate)
                }
            }
        })
        val sessionPlayer = object : ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands = Player.Commands.Builder()
                .addAll(super.getAvailableCommands())
                .addAll(
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                )
                .build()

            override fun seekToPrevious() = SystemPlaybackCommands.send(SystemPlaybackCommand.PREVIOUS)
            override fun seekToPreviousMediaItem() = SystemPlaybackCommands.send(SystemPlaybackCommand.PREVIOUS)
            override fun seekToNext() = SystemPlaybackCommands.send(SystemPlaybackCommand.NEXT)
            override fun seekToNextMediaItem() = SystemPlaybackCommands.send(SystemPlaybackCommand.NEXT)
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val sessionBuilder = MediaSession.Builder(this, sessionPlayer)
        if (launchIntent != null) {
            sessionBuilder.setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        mediaSession = sessionBuilder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        detailsLoader?.release()
        timerHandler.removeCallbacks(timerTick)
        usbAudioCoordinator?.release()
        usbAudioCoordinator = null
        mediaSession?.release()
        mediaSession = null
        routingProvider?.release()
        routingProvider = null
        exoPlayer?.release()
        exoPlayer = null
        retainedPlaybackSession.clear()
        MusicDiskCache.available()?.activeKey = null
        super.onDestroy()
    }
}
