package com.musicone

import com.musicone.source.sdk.SourcePlayerPresentation
import com.musicone.sourceintegration.SourcePackageController

internal val MusicSource.playerPresentation: SourcePlayerPresentation?
    get() = SourcePackageController.current()?.running(id)?.entry?.mediaPlayback as? SourcePlayerPresentation
