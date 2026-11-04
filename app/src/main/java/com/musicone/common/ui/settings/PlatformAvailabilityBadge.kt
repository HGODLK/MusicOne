package com.musicone

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.musicone.sourceintegration.SourcePackageController
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun PlatformAvailabilityBadge(source: MusicSource) {
    if (source == MusicSource.LOCAL) return
    val controller = SourcePackageController.get(LocalContext.current)
    val installed by controller.installed.collectAsState()
    val available = installed.any { it.id == source.id && !it.removed } && controller.running(source.id) != null
    if (available) return
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            "音源不可用",
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontSize = 10.sp,
            lineHeight = 12.sp,
        )
    }
}
