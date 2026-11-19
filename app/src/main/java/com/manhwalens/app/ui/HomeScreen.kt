package com.manhwalens.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.manhwalens.app.R
import com.manhwalens.app.capture.CaptureManager
import com.manhwalens.app.pipeline.CaptureResult

@Composable
fun HomeScreen(
    overlayGranted: Boolean,
    notificationsGranted: Boolean,
    sessionActive: Boolean,
    sessionPhase: CaptureManager.SessionPhase,
    bubbleEnabled: Boolean,
    working: CaptureManager.Status?,
    recent: CaptureResult?,
    onCaptureClick: () -> Unit,
    onStopSession: () -> Unit,
    onToggleBubble: () -> Unit,
    onEnableOverlay: () -> Unit,
    onSeeAllHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.size(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Capture button
        CaptureButton(
            working = working,
            sessionActive = sessionActive,
            sessionPhase = sessionPhase,
            onClick = onCaptureClick,
            onStopSession = onStopSession,
        )

        Text("Capture: $sessionPhase", style = MaterialTheme.typography.bodyMedium)
        if (sessionPhase == CaptureManager.SessionPhase.STARTING || (sessionActive && working != null)) {
            OutlinedButton(onClick = onStopSession, modifier = Modifier.fillMaxWidth()) { Text("إيقاف جلسة الالتقاط") }
        }
        // Permission status
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.home_ready_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                StatusRow(
                    icon = Icons.Filled.PhotoCamera,
                    label = stringResource(R.string.home_status_capture),
                    granted = sessionActive,
                    grantedLabel = stringResource(R.string.home_status_granted),
                    missingLabel = stringResource(R.string.home_status_missing),
                )
                StatusRow(
                    icon = Icons.Filled.BubbleChart,
                    label = stringResource(R.string.home_status_bubble),
                    granted = bubbleEnabled && overlayGranted,
                    grantedLabel = stringResource(R.string.home_status_granted),
                    missingLabel = stringResource(R.string.home_status_missing),
                    onFix = if (!overlayGranted) onEnableOverlay else null,
                )
                StatusRow(
                    icon = Icons.Filled.Notifications,
                    label = stringResource(R.string.home_status_notifications),
                    granted = notificationsGranted,
                    grantedLabel = stringResource(R.string.home_status_granted),
                    missingLabel = stringResource(R.string.home_status_missing),
                )
                OutlinedButton(
                    onClick = onToggleBubble,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        if (bubbleEnabled) stringResource(R.string.home_bubble_disable)
                        else stringResource(R.string.home_bubble_enable),
                    )
                }
            }
        }

        // Recent translation
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.home_recent_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onSeeAllHistory) { Text(stringResource(R.string.home_see_all)) }
                }
                if (recent == null) {
                    Text(
                        text = stringResource(R.string.home_recent_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = recent.preview.ifBlank { "…" },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // How it works
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(18.dp),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(10.dp))
                Column {
                    Text(
                        text = stringResource(R.string.home_tip_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.home_tip_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CaptureButton(
    working: CaptureManager.Status?,
    sessionActive: Boolean,
    sessionPhase: CaptureManager.SessionPhase,
    onClick: () -> Unit,
    onStopSession: () -> Unit,
) {
    val brush = Brush.linearGradient(
        colors = listOf(Color(0xFF7C3AED), Color(0xFFA855F7), Color(0xFFF59E0B)),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(brush)
            .clickable(enabled = working == null && sessionPhase !in listOf(CaptureManager.SessionPhase.STARTING, CaptureManager.SessionPhase.STOPPING), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (working != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(10.dp))
                Text(text = statusLabel(working), color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = null, tint = Color.White, modifier = Modifier.size(38.dp))
                Spacer(Modifier.height(10.dp))
                Text(
                    text = when (sessionPhase) {
                        CaptureManager.SessionPhase.STARTING -> "بدء الجلسة…"
                        CaptureManager.SessionPhase.STOPPING -> "إيقاف الجلسة…"
                        else -> stringResource(R.string.home_capture_button)
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                )
                if (sessionActive) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onStopSession) {
                        Icon(Icons.Filled.Stop, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.home_stop_session), color = Color.White.copy(alpha = 0.95f))
                    }
                }
            }
        }
    }
}

@Composable
private fun statusLabel(status: CaptureManager.Status): String = stringResource(
    when (status) {
        CaptureManager.Status.PREPARING -> R.string.status_preparing
        CaptureManager.Status.READING -> R.string.status_reading
        CaptureManager.Status.TRANSLATING -> R.string.status_translating
        CaptureManager.Status.DOWNLOADING_MODEL -> R.string.status_downloading_model
    },
)

@Composable
private fun StatusRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    granted: Boolean,
    grantedLabel: String,
    missingLabel: String,
    onFix: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (granted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (onFix != null) {
            TextButton(onClick = onFix) {
                Text(missingLabel, style = MaterialTheme.typography.labelMedium)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (granted) Color(0xFF34D399) else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = if (granted) grantedLabel else missingLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (granted) Color(0xFF34D399) else MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}
