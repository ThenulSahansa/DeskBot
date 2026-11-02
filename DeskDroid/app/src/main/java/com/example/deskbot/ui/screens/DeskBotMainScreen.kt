package com.example.deskbot.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.deskbot.MainActivity
import com.example.deskbot.R
import com.example.deskbot.TerminalLog
import com.example.deskbot.ui.components.FlatCard
import com.example.deskbot.ui.components.TerminalPanel
import com.example.deskbot.ui.theme.CatppuccinBase
import com.example.deskbot.ui.theme.CatppuccinBlue
import com.example.deskbot.ui.theme.CatppuccinCrust
import com.example.deskbot.ui.theme.CatppuccinGreen
import com.example.deskbot.ui.theme.CatppuccinMantle
import com.example.deskbot.ui.theme.CatppuccinMauve
import com.example.deskbot.ui.theme.CatppuccinOverlay0
import com.example.deskbot.ui.theme.CatppuccinOverlay1
import com.example.deskbot.ui.theme.CatppuccinPeach
import com.example.deskbot.ui.theme.CatppuccinRed
import com.example.deskbot.ui.theme.CatppuccinSapphire
import com.example.deskbot.ui.theme.CatppuccinSubtext0
import com.example.deskbot.ui.theme.CatppuccinSubtext1
import com.example.deskbot.ui.theme.CatppuccinSurface0
import com.example.deskbot.ui.theme.CatppuccinSurface1
import com.example.deskbot.ui.theme.CatppuccinSurface2
import com.example.deskbot.ui.theme.CatppuccinTeal
import com.example.deskbot.ui.theme.CatppuccinText

data class DeskBotUiState(
    val connectionState: MainActivity.ConnectionState = MainActivity.ConnectionState.IDLE,
    val connectionText: String = "DeskBot · Disconnected",
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val deviceName: String = "DeskBot ESP32",
    val telemetryStatus: String = "Offline",
    val telemetryRadio: String = "BLE 5.0",
    val telemetryMtu: String = "23 B",
    val currentVersion: String = "v1.0.3",
    val updateStatus: String = "Check for Updates",
    val isUpdateAvailable: Boolean = false,
    val latestVersionTag: String? = null,
    val releaseDate: String = "",
    val releaseNotes: String = "",
    val isOtaInProgress: Boolean = false,
    val otaPercentage: Int = 0,
    val otaStatusLabel: String = "Updating firmware…",
    val isTerminalVisible: Boolean = false,
    val isAiSessionActive: Boolean = false,
    val lastGeminiResponse: String = "",
    val isMuted: Boolean = false,
    val isAudioOutputConnected: Boolean = false
)

@Composable
fun DeskBotMainScreen(
    state: DeskBotUiState,
    onConnectClick: () -> Unit,
    onQuickScanClick: () -> Unit,
    onCheckUpdatesClick: () -> Unit,
    onInstallUpdateClick: () -> Unit,
    onStatusClick: () -> Unit,
    onToggleTerminal: () -> Unit,
    onToggleAiSession: () -> Unit,
    onToggleMute: () -> Unit
) {
    val scrollState = rememberScrollState()
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CatppuccinBase)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(top = statusBarPadding + 10.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Modern Flat Status Pill
            FlatCard(
                modifier = Modifier
                    .clickable { onStatusClick() }
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(20.dp),
                backgroundColor = CatppuccinSurface0,
                borderColor = if (state.isConnected) CatppuccinGreen else CatppuccinSurface1,
                borderWidth = 1.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    val iconRes = when (state.connectionState) {
                        MainActivity.ConnectionState.CONNECTED -> R.drawable.ic_check_circle
                        MainActivity.ConnectionState.SCANNING -> R.drawable.ic_antenna
                        MainActivity.ConnectionState.UPDATING -> R.drawable.ic_arrow_clockwise
                        else -> R.drawable.ic_bluetooth
                    }

                    val iconTint = when (state.connectionState) {
                        MainActivity.ConnectionState.CONNECTED -> CatppuccinGreen
                        MainActivity.ConnectionState.SCANNING -> CatppuccinMauve
                        MainActivity.ConnectionState.UPDATING -> CatppuccinPeach
                        else -> CatppuccinBlue
                    }

                    Icon(
                        painter = painterResource(id = iconRes),
                        contentDescription = "Status",
                        tint = iconTint,
                        modifier = Modifier.size(16.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = state.connectionText,
                        color = CatppuccinText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    if (state.connectionState == MainActivity.ConnectionState.SCANNING ||
                        state.connectionState == MainActivity.ConnectionState.CONNECTING ||
                        state.connectionState == MainActivity.ConnectionState.UPDATING
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            color = CatppuccinMauve,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (state.isConnected) CatppuccinGreen else CatppuccinOverlay0)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 2. Navigation Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "DeskBot",
                        color = CatppuccinText,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    )
                    Text(
                        text = "Smart Desktop Companion",
                        color = CatppuccinSubtext0,
                        fontSize = 13.sp
                    )
                }

                // Quick Scan Flat Icon Button
                FlatCard(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable { onQuickScanClick() },
                    shape = RoundedCornerShape(12.dp),
                    backgroundColor = CatppuccinSurface0,
                    borderColor = CatppuccinSurface1,
                    borderWidth = 1.dp
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_antenna),
                        contentDescription = "Scan",
                        tint = CatppuccinMauve,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 3. Hero Flat Device Card
            FlatCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                backgroundColor = CatppuccinSurface0,
                borderColor = if (state.isConnected) CatppuccinMauve else CatppuccinSurface1,
                borderWidth = 1.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Robot Flat Badge
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(CatppuccinMantle),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_robot),
                            contentDescription = "Robot",
                            tint = if (state.isConnected) CatppuccinGreen else CatppuccinMauve,
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = state.deviceName,
                        color = CatppuccinText,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = if (state.isConnected) "Active hardware link established" else "Ready to pair via BLE",
                        color = CatppuccinSubtext0,
                        fontSize = 13.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Primary Flat Action Button
                    val buttonBg = if (state.isConnected) CatppuccinRed else CatppuccinMauve
                    val buttonText = if (state.isConnected) "Disconnect" else "Connect to DeskBot"
                    val buttonIcon = if (state.isConnected) R.drawable.ic_close else R.drawable.ic_bluetooth

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(buttonBg)
                            .clickable { onConnectClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (state.isConnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = CatppuccinCrust,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Connecting…",
                                    color = CatppuccinCrust,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Icon(
                                    painter = painterResource(id = buttonIcon),
                                    contentDescription = null,
                                    tint = CatppuccinCrust,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = buttonText,
                                    color = CatppuccinCrust,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 4. System Telemetry Section
            Text(
                text = "SYSTEM TELEMETRY",
                color = CatppuccinOverlay1,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, bottom = 8.dp)
            )

            FlatCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                backgroundColor = CatppuccinSurface0,
                borderColor = CatppuccinSurface1,
                borderWidth = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Metric 1: Status
                    FlatCard(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        backgroundColor = CatppuccinMantle,
                        borderColor = CatppuccinSurface1,
                        borderWidth = 1.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("STATUS", color = CatppuccinOverlay1, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = state.telemetryStatus,
                                color = if (state.isConnected) CatppuccinGreen else CatppuccinSubtext0,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Metric 2: Link
                    FlatCard(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        backgroundColor = CatppuccinMantle,
                        borderColor = CatppuccinSurface1,
                        borderWidth = 1.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("LINK", color = CatppuccinOverlay1, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = state.telemetryRadio,
                                color = CatppuccinBlue,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Metric 3: MTU
                    FlatCard(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        backgroundColor = CatppuccinMantle,
                        borderColor = CatppuccinSurface1,
                        borderWidth = 1.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("MTU", color = CatppuccinOverlay1, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = state.telemetryMtu,
                                color = CatppuccinTeal,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 5. Firmware & Software OTA Section
            Text(
                text = "FIRMWARE & OTA",
                color = CatppuccinOverlay1,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, bottom = 8.dp)
            )

            FlatCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                backgroundColor = CatppuccinSurface0,
                borderColor = CatppuccinSurface1,
                borderWidth = 1.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(CatppuccinMantle),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_cpu),
                                    contentDescription = "CPU",
                                    tint = CatppuccinSapphire,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "DeskBot OS",
                                        color = CatppuccinText,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = state.currentVersion,
                                        color = CatppuccinMauve,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(CatppuccinSurface1)
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Text(
                                    text = state.updateStatus,
                                    color = if (state.isUpdateAvailable) CatppuccinPeach else CatppuccinSubtext0,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // Check / Update Button
                        FlatCard(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onCheckUpdatesClick() },
                            shape = RoundedCornerShape(10.dp),
                            backgroundColor = if (state.isUpdateAvailable) CatppuccinPeach else CatppuccinSurface1,
                            borderColor = Color.Transparent,
                            borderWidth = 0.dp
                        ) {
                            Text(
                                text = if (state.isUpdateAvailable) "Update" else "Check",
                                color = if (state.isUpdateAvailable) CatppuccinCrust else CatppuccinText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                            )
                        }
                    }

                    // Active OTA Progress Indicator
                    AnimatedVisibility(
                        visible = state.isOtaInProgress,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(modifier = Modifier.padding(top = 16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = state.otaStatusLabel, color = CatppuccinText, fontSize = 12.sp)
                                Text(text = "${state.otaPercentage}%", color = CatppuccinMauve, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { state.otaPercentage / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = CatppuccinMauve,
                                trackColor = CatppuccinSurface1
                            )
                        }
                    }

                    // Release Notes Card (when update available)
                    AnimatedVisibility(
                        visible = state.isUpdateAvailable && !state.isOtaInProgress,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        FlatCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp),
                            shape = RoundedCornerShape(12.dp),
                            backgroundColor = CatppuccinMantle,
                            borderColor = CatppuccinSurface1,
                            borderWidth = 1.dp
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "DeskBot ${state.latestVersionTag ?: ""} Available",
                                    color = CatppuccinPeach,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (state.releaseDate.isNotEmpty()) {
                                    Text(
                                        text = "Released: ${state.releaseDate}",
                                        color = CatppuccinOverlay1,
                                        fontSize = 11.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = state.releaseNotes.ifEmpty { "Bug fixes and performance improvements." },
                                    color = CatppuccinSubtext0,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(42.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(CatppuccinMauve)
                                        .clickable { onInstallUpdateClick() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Install ${state.latestVersionTag ?: "Update"}",
                                        color = CatppuccinCrust,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 6. AI Voice Assistant Section
            Text(
                text = "AI VOICE ASSISTANT",
                color = CatppuccinOverlay1,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, bottom = 8.dp)
            )

            FlatCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                backgroundColor = CatppuccinSurface0,
                borderColor = if (state.isAiSessionActive) CatppuccinMauve else CatppuccinSurface1,
                borderWidth = 1.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(CatppuccinMantle),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_sparkles),
                                    contentDescription = "AI",
                                    tint = if (state.isAiSessionActive) CatppuccinMauve else CatppuccinOverlay1,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = "Gemini Live",
                                    color = CatppuccinText,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (state.isAiSessionActive) "Session active · Listening" else "Voice AI · Idle",
                                    color = if (state.isAiSessionActive) CatppuccinGreen else CatppuccinSubtext0,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // Start / Stop Session Button
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Mute / Unmute Button — only visible when audio output device is connected
                            if (state.isAudioOutputConnected) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(if (state.isMuted) CatppuccinRed else CatppuccinTeal)
                                        .clickable { onToggleMute() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(
                                            id = if (state.isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic
                                        ),
                                        contentDescription = if (state.isMuted) "Unmute TTS" else "Mute TTS",
                                        tint = CatppuccinCrust,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (state.isAiSessionActive) CatppuccinRed else CatppuccinMauve)
                                    .clickable { onToggleAiSession() }
                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (state.isAiSessionActive) "Stop" else "Start",
                                    color = CatppuccinCrust,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Last response preview
                    AnimatedVisibility(
                        visible = state.lastGeminiResponse.isNotEmpty(),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        FlatCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            shape = RoundedCornerShape(12.dp),
                            backgroundColor = CatppuccinMantle,
                            borderColor = CatppuccinSurface1,
                            borderWidth = 1.dp
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Last Response",
                                    color = CatppuccinOverlay1,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = state.lastGeminiResponse,
                                    color = CatppuccinText,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 7. Terminal Toggle
            FlatCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleTerminal() },
                shape = RoundedCornerShape(14.dp),
                backgroundColor = CatppuccinSurface0,
                borderColor = CatppuccinSurface1,
                borderWidth = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_chevron_right),
                            contentDescription = "Terminal",
                            tint = CatppuccinGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Terminal",
                            color = CatppuccinText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${TerminalLog.entries.size} lines",
                            color = CatppuccinOverlay1,
                            fontSize = 11.sp
                        )
                    }

                    Switch(
                        checked = state.isTerminalVisible,
                        onCheckedChange = { onToggleTerminal() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CatppuccinCrust,
                            checkedTrackColor = CatppuccinGreen,
                            uncheckedThumbColor = CatppuccinOverlay1,
                            uncheckedTrackColor = CatppuccinSurface1,
                            uncheckedBorderColor = CatppuccinSurface2
                        )
                    )
                }
            }

            // Extra bottom padding when terminal is visible to avoid content being hidden
            Spacer(modifier = Modifier.height(if (state.isTerminalVisible) 280.dp else 32.dp))
        }

        // Terminal Panel — overlays at bottom, outside scrollable Column
        AnimatedVisibility(
            visible = state.isTerminalVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            TerminalPanel(
                entries = TerminalLog.entries,
                onClear = { TerminalLog.clear() }
            )
        }
    }
}
