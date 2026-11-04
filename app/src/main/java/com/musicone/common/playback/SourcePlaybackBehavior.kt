package com.musicone

import com.musicone.source.sdk.SourceLyricBehavior
import com.musicone.source.sdk.SourcePlaybackBehavior
import com.musicone.sourceintegration.SourcePackageController

internal val MusicSource.playbackBehavior: SourcePlaybackBehavior?
    get() = SourcePackageController.current()?.running(id)?.entry?.mediaPlayback as? SourcePlaybackBehavior

internal val MusicSource.lyricBehavior: SourceLyricBehavior?
    get() = SourcePackageController.current()?.running(id)?.entry?.lyricProvider as? SourceLyricBehavior
