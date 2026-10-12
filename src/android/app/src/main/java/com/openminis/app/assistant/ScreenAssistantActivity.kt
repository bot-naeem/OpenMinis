package com.openminis.app.assistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.openminis.app.MainActivity
import com.openminis.app.R
import com.openminis.app.ui.chat.ChatViewModelStore
import com.openminis.app.ui.chat.InputAttachment
import com.openminis.app.ui.theme.MinisTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * ScreenAssistantActivity provides a floating prompt interface that perceives
 * the current screen context and dispatches multimodal tasks to Minis Agent.
 */
class ScreenAssistantActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Close system dialogs / notification drawer explicitly if needed
        try {
            @Suppress("DEPRECATION")
            sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        } catch (_: Throwable) {}

        setContent {
            MinisTheme {
                ScreenAssistantScreen(
                    onDismiss = { finish() },
                    onSubmit = { prompt, screenContext ->
                        submitPrompt(prompt, screenContext)
                    },
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                )
            }
        }
    }

    private fun submitPrompt(userPrompt: String, screenContext: ScreenContextCapturer.ScreenContext) {
        val prompt = userPrompt.trim().ifBlank {
            getString(R.string.screen_assistant_default_prompt)
        }

        val fullPrompt = buildString {
            append(prompt)
            if (!screenContext.extractedText.isNullOrBlank()) {
                val appLabel = screenContext.appName ?: screenContext.packageName ?: ""
                append("\n\n---\n")
                if (appLabel.isNotBlank()) {
                    append("[来自当前页面: $appLabel]\n")
                }
                append(screenContext.extractedText)
            }
        }

        val attachments = mutableListOf<InputAttachment>()
        if (screenContext.screenshotUri != null && screenContext.screenshotFile != null) {
            attachments.add(
                InputAttachment(
                    fileName = screenContext.screenshotFile.name,
                    uri = screenContext.screenshotUri,
                    mimeType = "image/jpeg",
                    kind = InputAttachment.Kind.IMAGE,
                )
            )
        }

        val sessionId = UUID.randomUUID().toString()
        ChatViewModelStore.stashPendingTransfer(
            ChatViewModelStore.PendingTransfer(
                inputText = fullPrompt,
                attachments = attachments,
                targetId = sessionId,
            )
        )

        val intent = Intent(this, MainActivity::class.java).apply {
            data = Uri.parse("minis://session/$sessionId")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenAssistantScreen(
    onDismiss: () -> Unit,
    onSubmit: (prompt: String, context: ScreenContextCapturer.ScreenContext) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    var isCapturing by remember { mutableStateOf(true) }
    var screenContext by remember {
        mutableStateOf(ScreenContextCapturer.ScreenContext())
    }
    var promptText by remember { mutableStateOf("") }

    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext

    // Wait briefly for notification shade to collapse, then capture screen
    LaunchedEffect(Unit) {
        delay(180)
        withContext(Dispatchers.IO) {
            val captured = ScreenContextCapturer.captureCurrentScreen(context)
            withContext(Dispatchers.Main) {
                screenContext = captured
                isCapturing = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = true,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { /* prevent click-through to dismiss */ },
                    )
                    .imePadding()
                    .navigationBarsPadding(),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 16.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    // Header row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Text(
                                text = stringResource(R.string.screen_assistant_title),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            val appName = screenContext.appName
                            if (!appName.isNullOrBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                ) {
                                    Text(
                                        text = appName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }

                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Screen preview or A11y hint
                    if (isCapturing) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.screen_assistant_capturing),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (screenContext.screenshotFile != null) {
                        // Captured screenshot summary card
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    RoundedCornerShape(12.dp),
                                )
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AsyncImage(
                                model = screenContext.screenshotFile,
                                contentDescription = "Screenshot preview",
                                modifier = Modifier
                                    .size(width = 46.dp, height = 64.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Crop,
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.screen_assistant_captured_badge),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                val textLen = screenContext.extractedText?.length ?: 0
                                if (textLen > 0) {
                                    Text(
                                        text = stringResource(R.string.screen_assistant_text_extracted, textLen),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    } else if (!screenContext.isA11yActive) {
                        // Accessibility not enabled hint
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.screen_assistant_a11y_missing_title),
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    Text(
                                        text = stringResource(R.string.screen_assistant_a11y_missing_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = onOpenAccessibilitySettings) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = "Open Settings",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Preset Quick Action Chips
                    val quickActions = listOf(
                        stringResource(R.string.screen_assistant_action_summarize),
                        stringResource(R.string.screen_assistant_action_translate),
                        stringResource(R.string.screen_assistant_action_extract),
                        stringResource(R.string.screen_assistant_action_explain),
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        quickActions.forEach { action ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                modifier = Modifier.clickable {
                                    promptText = action
                                },
                            ) {
                                Text(
                                    text = action,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Prompt Input Row
                    OutlinedTextField(
                        value = promptText,
                        onValueChange = { promptText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.screen_assistant_input_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        shape = RoundedCornerShape(16.dp),
                        trailingIcon = {
                            IconButton(
                                onClick = { onSubmit(promptText, screenContext) },
                                enabled = !isCapturing,
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send",
                                    tint = if (promptText.isNotBlank()) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        ),
                        singleLine = false,
                        maxLines = 3,
                    )
                }
            }
        }
    }
}
