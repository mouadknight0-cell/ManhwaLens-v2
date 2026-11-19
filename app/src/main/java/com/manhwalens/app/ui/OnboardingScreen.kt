package com.manhwalens.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.manhwalens.app.R

@Composable
fun OnboardingScreen(
    overlayGranted: Boolean,
    notificationsGranted: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestNotifications: () -> Unit,
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val brush = Brush.verticalGradient(
        colors = listOf(Color(0xFF1B1233), Color(0xFF0D0A1A)),
        startY = 0f,
        endY = 1400f,
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(brush)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(120.dp).clip(RoundedCornerShape(28.dp)),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = Color(0xFFF3EFFA),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFFB9B3D6),
        )

        Spacer(Modifier.height(32.dp))
        OnboardingStep(
            icon = Icons.Filled.CameraAlt,
            title = stringResource(R.string.onboarding_step_capture_title),
            body = stringResource(R.string.onboarding_step_capture_body),
        )
        OnboardingStep(
            icon = Icons.Filled.BubbleChart,
            title = stringResource(R.string.onboarding_step_bubble_title),
            body = stringResource(R.string.onboarding_step_bubble_body),
        )
        OnboardingStep(
            icon = Icons.Filled.Shield,
            title = stringResource(R.string.onboarding_step_private_title),
            body = stringResource(R.string.onboarding_step_private_body),
        )

        Spacer(Modifier.height(28.dp))

        if (!overlayGranted) {
            OutlinedButton(
                onClick = onRequestOverlay,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Filled.BubbleChart, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.onboarding_allow_overlay))
            }
            Spacer(Modifier.height(10.dp))
        }
        if (!notificationsGranted) {
            OutlinedButton(
                onClick = onRequestNotifications,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.onboarding_allow_notifications))
            }
            Spacer(Modifier.height(10.dp))
        }

        Button(
            onClick = onGetStarted,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
        ) {
            Text(
                text = stringResource(R.string.onboarding_start),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
            )
        }

        Spacer(Modifier.height(36.dp))
    }
}

@Composable
private fun OnboardingStep(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF221D3E),
            modifier = Modifier.size(46.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color(0xFFC4B5FD),
                modifier = Modifier.padding(11.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF3EFFA),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB9B3D6),
            )
        }
    }
}
