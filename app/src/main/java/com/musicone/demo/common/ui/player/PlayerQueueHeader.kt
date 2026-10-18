package com.musicone.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 拉杆与标题各占独立位置，数量和标题使用相同字号、行高并垂直居中。 */
@Composable
internal fun PlayerQueueHeader(count: Int, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(38.dp, 4.dp)
            .background(Color.White.copy(alpha = .28f), RoundedCornerShape(2.dp)))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("播放列表", fontSize = 20.sp, lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(count.toString(), fontSize = 20.sp, lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = .55f))
        }
    }
}
