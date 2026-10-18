package com.musicone.demo

import android.app.PendingIntent
import android.content.Intent
import android.os.IBinder
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.musicone.nativeaudio.NativeAudioOutputPolicy
import com.musicone.nativeaudio.NativeAudioRoutingMediaSourceFactory

@androidx.annotation.OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null
    private var systemMediaPlayer: SystemMediaPlayer? = null
    private var usbAudioCoordinator: UsbAudioServiceCoordinator? = null
    private var detailsLoader: PlaybackDetailsLoader? = null
    private var audioFocusCoordinator: PlaybackAudioFocusCoordinator? = null
    private var routingProvider: RoutingAudioTrackProvider? = null
    private var runtime: MusicPlaybackRuntime? = null
    private var notificationDiagnostics: SystemMediaNotificationDiagnostics? = null
    private val timerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            if (PlaybackSleepTimer.expired()) { exoPlayer?.pause(); PlaybackSleepTimer.set(0) }
            timerHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationDiagnostics = SystemMediaNotificationDiagnostics(this, foreground = { isPlaybackOngoing })
            .also { setMediaNotificationProvider(it) }
        PlaybackOptions.initialize(this)
        DevicePlaybackOptions.initialize(this)
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            // 工厂专用 UA 会覆盖 DataSpec 的请求头；默认属性允许补充音源携带自己的 UA。
            .setDefaultRequestProperties(mapOf("User-Agent" to PlatformHttp.DEFAULT_USER_AGENT))
        val upstreamDataSourceFactory = SupplementalPlaybackRequests.factory(
            MusicDiskCache.get(this).factory(DefaultDataSource.Factory(this, httpDataSourceFactory)),
        )
        val defaultMediaSourceFactory = DefaultMediaSourceFactory(upstreamDataSourceFactory)
        val mediaSourceFactory = NativeAudioRoutingMediaSourceFactory(
            defaultFactory = defaultMediaSourceFactory,
            dataSourceFactory = upstreamDataSourceFactory,
            targetDsdSampleRate = NativeAudioOutputPolicy.DEFAULT_DSD_PCM_SAMPLE_RATE,
            context = this,
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
                !DevicePlaybackOptions.concurrentPlayback.value,
            )
            setHandleAudioBecomingNoisy(true)
        }
        exoPlayer = player
        audioFocusCoordinator = PlaybackAudioFocusCoordinator(player).also { it.start() }
        player.addListener(PlaybackServiceDiagnostics { isPlaybackOngoing })
        val playback = MusicPlaybackRuntimeOwner.acquire(application, this)
        runtime = playback
        detailsLoader = PlaybackDetailsLoader(this, player).also(player::addListener)
        usbAudioCoordinator = renderersFactory.usbAudioSink?.let { sink ->
            UsbAudioServiceCoordinator(this, player, sink, playback::pauseForUsbOutputLoss).also { it.start() }
        }
        timerHandler.post(timerTick)
        val audioMetadata = PlaybackAudioMetadataListener(player)
        player.addListener(audioMetadata)
        player.addAnalyticsListener(audioMetadata)
        val diagnostics = SystemMediaDiagnostics()
        val sessionPlayer = SystemMediaPlayer(player, playback::setPlaying,
            playback::previous, playback::next, playback::seekFromSystem,
            recordCommand = { diagnostics.command(it, mediaSession?.controllerForCurrentRequest) },
        )
        systemMediaPlayer = sessionPlayer
        sessionPlayer.addListener(diagnostics)
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val sessionBuilder = MediaSession.Builder(this, sessionPlayer).setCallback(diagnostics)
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
        // 在恢复待播放请求之前注册会话，后台通知管理不依赖页面控制器连接时机。
        addSession(requireNotNull(mediaSession))
        playback.attachPlayer(player)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onBind(intent: Intent?): IBinder? {
        val browserIntent = VivoSystemMedia.browserIntent(intent)
        val binder = super.onBind(browserIntent)
        if (intent?.action == VivoSystemMedia.SERVICE_ACTION) {
            android.util.Log.i("MusicOneSystemMedia", "vivo 媒体服务绑定 已提供接口=${binder != null}")
        }
        return binder
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired)
        notificationDiagnostics?.inspectAfterUpdate()
    }

    override fun onDestroy() {
        android.util.Log.i("MusicPlaybackService", "播放服务销毁")
        notificationDiagnostics?.close()
        notificationDiagnostics = null
        exoPlayer?.let { runtime?.detachPlayer(it) }
        MusicPlaybackRuntimeOwner.release(this)
        runtime = null
        detailsLoader?.release()
        audioFocusCoordinator?.release()
        audioFocusCoordinator = null
        timerHandler.removeCallbacks(timerTick)
        usbAudioCoordinator?.release()
        usbAudioCoordinator = null
        mediaSession?.release()
        mediaSession = null
        routingProvider?.release()
        routingProvider = null
        systemMediaPlayer?.release() ?: exoPlayer?.release()
        systemMediaPlayer = null
        exoPlayer = null
        retainedPlaybackSession.clear()
        MusicDiskCache.available()?.activeKey = null
        super.onDestroy()
    }
}
