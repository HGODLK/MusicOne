package com.musicone

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
    private var desktopLyrics: DesktopLyricsCoordinator? = null
    private val presentationScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
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
        val notifications = requireNotNull(notificationDiagnostics)
        setMediaNotificationProvider(notifications)
        PlaybackOptions.initialize(this)
        LgHiResPreferences.initialize(this)
        DevicePlaybackOptions.initialize(this)
        AutoMixPreferences.initialize(this)
        val preloadBandwidth = PlaybackPreloadBandwidth()
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            // 用 setDefaultRequestProperties 设默认 UA，这样单个请求自带的 UA 不会被覆盖。
            .setDefaultRequestProperties(mapOf("User-Agent" to PlatformHttp.DEFAULT_USER_AGENT))
        val upstreamDataSourceFactory = SupplementalPlaybackRequests.factory(
            MusicDiskCache.get(this).factory(DefaultDataSource.Factory(this,
                playbackPreloadNetworkFactory(httpDataSourceFactory, preloadBandwidth))),
        )
        val defaultMediaSourceFactory = DefaultMediaSourceFactory(upstreamDataSourceFactory)
        val routingMediaSourceFactory = NativeAudioRoutingMediaSourceFactory(
            defaultFactory = defaultMediaSourceFactory,
            dataSourceFactory = upstreamDataSourceFactory,
            targetDsdSampleRate = NativeAudioOutputPolicy.DEFAULT_DSD_PCM_SAMPLE_RATE,
            context = this,
        )
        // 自然过渡的离线解码与播放共用同一工厂，保证解码器与时间轴一致
        AutoMixEngine.mediaSourceFactory = routingMediaSourceFactory
        AutoMixEngine.dataSourceFactory = upstreamDataSourceFactory
        val mediaSourceFactory = AutoMixMediaSourceFactory(routingMediaSourceFactory)
        val renderersFactory = HiResCompatibleRenderersFactory(this)
        routingProvider = renderersFactory.routingProvider
        val player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(playbackLoadControl())
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
            UsbAudioServiceCoordinator(this, player, sink, playback::pauseForUsbOutputLoss,
                renderersFactory.lgAudioSink).also { it.start() }
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
        // 先注册 MediaSession 再恢复播放，避免后台通知状态依赖页面何时连上服务。
        addSession(requireNotNull(mediaSession))
        playback.attachPlayer(player, preloadBandwidth)
        val presentations = playbackSystemPresentations(this, playback, player).sharedSystemPresentations(presentationScope)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            desktopLyrics = DesktopLyricsCoordinator(this, presentations, playback::togglePlay).also { it.start() }
        }
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
        if (android.os.Build.VERSION.SDK_INT >= 31) desktopLyrics?.close()
        desktopLyrics = null
        presentationScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
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
