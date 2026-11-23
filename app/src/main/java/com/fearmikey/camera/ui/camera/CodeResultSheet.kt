package com.fearmikey.camera.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fearmikey.camera.domain.model.CodeFormat
import com.fearmikey.camera.domain.model.CodePayload
import com.fearmikey.camera.domain.model.ScannedCode
import com.fearmikey.camera.ui.theme.CameraAppTheme
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

/** Bottom sheet with every scanned code: parsed details, the main action, copy and share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeResultSheet(
    codes: List<ScannedCode>,
    onDismiss: () -> Unit,
    onScanImage: (() -> Unit)?,
) {
    // Follows the system light/dark theme like the quick settings sheet.
    CameraAppTheme {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = codes.size == 1),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            CodeResultSheetContent(codes = codes, onScanImage = onScanImage)
        }
    }
}

@Composable
fun CodeResultSheetContent(codes: List<ScannedCode>, onScanImage: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (codes.size > 1) {
            Text("${codes.size} codes found", style = MaterialTheme.typography.titleLarge)
        }
        codes.forEach { CodeCard(it) }
        if (onScanImage != null) {
            TextButton(onClick = onScanImage, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Scan an image")
            }
        }
    }
}

@Composable
private fun CodeCard(code: ScannedCode) {
    val context = LocalContext.current
    val payload = code.payload
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        CodeActions.icon(payload),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        CodeActions.summary(payload).ifEmpty { CodeActions.title(payload) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${CodeActions.title(payload)} · ${code.format.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            PayloadDetails(payload)

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { CodeActions.runPrimary(context, payload) }, modifier = Modifier.weight(1f)) {
                    Text(CodeActions.primaryLabel(payload))
                }
                if (payload !is CodePayload.Text) {
                    val wifiPassword = (payload as? CodePayload.Wifi)?.password
                    FilledTonalIconButton(onClick = {
                        if (wifiPassword != null) {
                            CodeActions.copy(context, wifiPassword, label = "Wi-Fi password", sensitive = true)
                        } else {
                            CodeActions.copy(context, payload.raw)
                        }
                    }) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = if (wifiPassword != null) "Copy password" else "Copy"
                        )
                    }
                }
                FilledTonalIconButton(onClick = { CodeActions.share(context, payload.raw) }) {
                    Icon(Icons.Default.Share, contentDescription = "Share")
                }
            }
        }
    }
}

@Composable
private fun PayloadDetails(payload: CodePayload) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (payload) {
            is CodePayload.Url -> {
                if (payload.hasUserInfo) {
                    Warning("This link hides its real address. It actually opens ${payload.host}.")
                } else if (!payload.isSecure) {
                    Warning("Not a secure (https) link.")
                }
                Detail("Address", payload.url, monospace = true)
            }
            is CodePayload.Wifi -> {
                Detail("Security", payload.security.label + if (payload.hidden) " · hidden network" else "")
                payload.password?.let { PasswordDetail(it) }
            }
            is CodePayload.Contact -> {
                payload.organization?.let { Detail("Organization", it) }
                payload.title?.let { Detail("Title", it) }
                payload.phones.forEach { Detail("Phone", it) }
                payload.emails.forEach { Detail("Email", it) }
                payload.address?.let { Detail("Address", it) }
                payload.url?.let { Detail("Website", it) }
                payload.note?.let { Detail("Note", it) }
            }
            is CodePayload.Event -> {
                payload.startMillis?.let { Detail(if (payload.allDay) "Date" else "Starts", formatTime(it, payload.allDay)) }
                payload.endMillis?.let { end ->
                    // All-day ends are exclusive; only show a range for multi-day events.
                    val dayMs = 24L * 60 * 60 * 1000
                    if (!payload.allDay) Detail("Ends", formatTime(end, false))
                    else if (payload.startMillis != null && end - payload.startMillis > dayMs) Detail("Until", formatTime(end - dayMs, true))
                }
                payload.location?.let { Detail("Location", it) }
                payload.description?.let { Detail("Details", it) }
            }
            is CodePayload.Email -> {
                payload.subject?.let { Detail("Subject", it) }
                payload.body?.let { Detail("Message", it) }
            }
            is CodePayload.Sms -> payload.body?.let { Detail("Message", it) }
            is CodePayload.Phone -> Unit
            is CodePayload.Geo -> Detail("Coordinates", "%.6f, %.6f".format(java.util.Locale.US, payload.latitude, payload.longitude))
            is CodePayload.Product -> Detail("Number", payload.gtin, monospace = true)
            is CodePayload.Text -> Detail("Content", payload.raw)
        }
    }
}

@Composable
private fun Detail(label: String, value: String, monospace: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PasswordDetail(password: String) {
    var visible by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Password", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (visible) password else "•".repeat(password.length.coerceAtMost(16)),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
        }
        IconButton(onClick = { visible = !visible }) {
            Icon(
                if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = if (visible) "Hide password" else "Show password"
            )
        }
    }
}

@Composable
private fun Warning(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

private fun formatTime(millis: Long, allDay: Boolean): String {
    val format = if (allDay) {
        // All-day events are stored at UTC midnight.
        DateFormat.getDateInstance(DateFormat.MEDIUM).apply { timeZone = TimeZone.getTimeZone("UTC") }
    } else {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    }
    return format.format(Date(millis))
}

@Preview(widthDp = 400, heightDp = 900)
@Composable
private fun CodeResultSheetPreview() {
    CameraAppTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            CodeResultSheetContent(
                codes = listOf(
                    ScannedCode("WIFI:T:WPA;S:Home Network;P:correct horse;;", CodeFormat.QR_CODE),
                    ScannedCode("http://login.example.com@phish.example.net/account", CodeFormat.QR_CODE),
                    ScannedCode("BEGIN:VEVENT\nSUMMARY:Team lunch\nDTSTART:20261204T120000Z\nDTEND:20261204T130000Z\nLOCATION:Cafe\nEND:VEVENT", CodeFormat.QR_CODE),
                    ScannedCode("4006381333931", CodeFormat.EAN_13),
                ),
                onScanImage = {},
            )
        }
    }
}
