package com.example.deskbot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.deskbot.LogEntry
import com.example.deskbot.LogLevel
import com.example.deskbot.R
import com.example.deskbot.ui.theme.CatppuccinBlue
import com.example.deskbot.ui.theme.CatppuccinCrust
import com.example.deskbot.ui.theme.CatppuccinGreen
import com.example.deskbot.ui.theme.CatppuccinMauve
import com.example.deskbot.ui.theme.CatppuccinOverlay1
import com.example.deskbot.ui.theme.CatppuccinPeach
import com.example.deskbot.ui.theme.CatppuccinRed
import com.example.deskbot.ui.theme.CatppuccinSurface0
import com.example.deskbot.ui.theme.CatppuccinSurface1
import com.example.deskbot.ui.theme.CatppuccinTeal
import com.example.deskbot.ui.theme.CatppuccinText
import com.example.deskbot.ui.theme.CatppuccinYellow

@Composable
fun TerminalPanel(
    entries: List<LogEntry>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom on new entries
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }

    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.4f)
            .clip(shape)
            .background(CatppuccinCrust, shape)
            .border(1.dp, CatppuccinSurface1, shape)
    ) {
        // Header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CatppuccinSurface0)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_chevron_right),
                    contentDescription = "Terminal",
                    tint = CatppuccinGreen,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "TERMINAL",
                    color = CatppuccinOverlay1,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${entries.size} lines",
                    color = CatppuccinOverlay1.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Clear button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(CatppuccinSurface1)
                    .clickable { onClear() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Clear",
                    color = CatppuccinText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Log entries
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 36.dp)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            items(entries) { entry ->
                TerminalLogLine(entry)
            }
        }
    }
}

@Composable
private fun TerminalLogLine(entry: LogEntry) {
    val tagColor = when (entry.tag) {
        "BLE" -> CatppuccinBlue
        "AUDIO" -> CatppuccinTeal
        "GEMINI" -> CatppuccinMauve
        "DISPLAY" -> CatppuccinPeach
        "SYSTEM" -> CatppuccinOverlay1
        else -> CatppuccinOverlay1
    }

    val levelColor = when (entry.level) {
        LogLevel.INFO -> CatppuccinText
        LogLevel.WARN -> CatppuccinYellow
        LogLevel.ERROR -> CatppuccinRed
        LogLevel.SUCCESS -> CatppuccinGreen
    }

    val annotatedText = buildAnnotatedString {
        withStyle(SpanStyle(color = CatppuccinOverlay1.copy(alpha = 0.7f))) {
            append("[${entry.timestamp}] ")
        }
        withStyle(SpanStyle(color = tagColor, fontWeight = FontWeight.Bold)) {
            append("[${entry.tag}] ")
        }
        withStyle(SpanStyle(color = levelColor)) {
            append(entry.message)
        }
    }

    Text(
        text = annotatedText,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        lineHeight = 16.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
    )
}
