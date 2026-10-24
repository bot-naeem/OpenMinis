package com.openminis.app.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.openminis.app.R
import com.openminis.app.speech.ondevice.OnDeviceSpeechModelManager
import com.openminis.app.speech.ondevice.OnDeviceSpeechModelManager.ModelState
import com.openminis.app.speech.ondevice.SherpaOnnxSpeechRecognitionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

@Composable
fun OnDeviceSpeechSettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val modelState by OnDeviceSpeechModelManager.modelState.collectAsState()
    val isPreferred by OnDeviceSpeechModelManager.isPreferred.collectAsState()
    val language by OnDeviceSpeechModelManager.language.collectAsState()
    val useItn by OnDeviceSpeechModelManager.useItn.collectAsState()
    val numThreads by OnDeviceSpeechModelManager.numThreads.collectAsState()

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var showThreadsPicker by remember { mutableStateOf(false) }

    // Quick Test State
    var isTestRecording by remember { mutableStateOf(false) }
    var testResultText by remember { mutableStateOf<String?>(null) }
    var testLatencyMs by remember { mutableStateOf<Long?>(null) }
    var testRecordingJob by remember { mutableStateOf<Job?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (!granted) {
                Toast.makeText(context, "需要录音权限以进行测试", Toast.LENGTH_SHORT).show()
            }
        }
    )

    val isReady = modelState is ModelState.Ready

    SettingsScaffold(
        title = "端侧离线语音 (SenseVoice)",
        onBack = onBack,
    ) {
        // ── ① Master Switch ───────────────────────────────────────────
        SettingsSection(
            header = "端侧离线语音总控",
            footer = "当离线模型已下载就绪时，长按电源键与全局语音输入将优先直接在手机 CPU (ARM NEON) 毫秒级推理，无需联网且零 API 消耗。",
        ) {
            SettingsSwitchRow(
                title = "优先使用端侧离线识别",
                subtitle = when {
                    !isReady -> "模型未下载就绪，无法开启"
                    isPreferred -> "已优先使用端侧离线识别引擎 (SenseVoice)"
                    else -> "已禁用端侧识别，退回系统或云端 ASR"
                },
                checked = isPreferred && isReady,
                enabled = isReady,
                onCheckedChange = { OnDeviceSpeechModelManager.setPreferred(context, it) },
                icon = Icons.Outlined.Bolt,
                iconColor = if (isPreferred && isReady) Color(0xFFFF9500) else MaterialTheme.colorScheme.onSurfaceVariant,
                showDivider = false,
            )
        }

        // ── ② Offline Model Management ──────────────────────────────
        SettingsSection(
            header = "离线语音模型管理",
            footer = "模型基于阿里 SenseVoiceSmall INT8 量化微调，仅约 75MB，支持普通话、粤语、英语、日语与韩语，下载完成后永久保存在手机私有存储中。",
        ) {
            SettingsRow(
                title = "阿里 SenseVoiceSmall INT8",
                subtitle = when (val state = modelState) {
                    is ModelState.Ready -> {
                        val mb = state.totalSizeBytes / (1024f * 1024f)
                        "已就绪 · 本地存储 %.1f MB · 脱机离线可用".format(mb)
                    }
                    is ModelState.Downloading -> state.currentStage
                    is ModelState.NotDownloaded -> "未下载 · 需下载约 75 MB (国内高速 CDN 直连)"
                    is ModelState.Error -> "下载失败: ${state.message}，点击重试"
                },
                icon = Icons.Outlined.GraphicEq,
                iconColor = when (modelState) {
                    is ModelState.Ready -> Color(0xFF34C759)
                    is ModelState.Downloading -> Color(0xFF007AFF)
                    else -> Color(0xFFFF9500)
                },
                showChevron = false,
                trailing = {
                    Text(
                        text = when (modelState) {
                            is ModelState.Ready -> "已就绪"
                            is ModelState.Downloading -> "${((modelState as ModelState.Downloading).progress * 100).roundToInt()}%"
                            is ModelState.Error -> "出错"
                            is ModelState.NotDownloaded -> "未安装"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = when (modelState) {
                            is ModelState.Ready -> Color(0xFF34C759)
                            is ModelState.Downloading -> Color(0xFF007AFF)
                            is ModelState.Error -> Color(0xFFFF3B30)
                            is ModelState.NotDownloaded -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                showDivider = true,
            )

            // Download Progress Bar (visible only when downloading)
            AnimatedVisibility(visible = modelState is ModelState.Downloading) {
                val state = modelState as? ModelState.Downloading
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { state?.progress ?: 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = state?.currentStage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { OnDeviceSpeechModelManager.cancelDownload() }) {
                            Text("取消", color = Color(0xFFFF3B30))
                        }
                    }
                }
            }

            // Action: Download / Redownload
            if (!isReady && modelState !is ModelState.Downloading) {
                SettingsRow(
                    title = "一键下载离线语音模型 (约 75 MB)",
                    subtitle = "连接 ModelScope 高速节点，下载并自动校验安装",
                    icon = Icons.Outlined.CloudDownload,
                    iconColor = Color(0xFF007AFF),
                    onClick = { OnDeviceSpeechModelManager.startDownload(context) },
                    showChevron = true,
                    showDivider = false,
                )
            } else if (isReady) {
                SettingsRow(
                    title = "重新下载 / 覆盖更新模型",
                    subtitle = "重新拉取最新权重并覆盖本地缓存",
                    icon = Icons.Outlined.Refresh,
                    iconColor = Color(0xFF007AFF),
                    onClick = { OnDeviceSpeechModelManager.startDownload(context) },
                    showChevron = true,
                    showDivider = true,
                )
                SettingsRow(
                    title = "删除本地离线模型",
                    subtitle = "释放手机内部约 75 MB 存储空间",
                    icon = Icons.Outlined.Delete,
                    iconColor = Color(0xFFFF3B30),
                    onClick = { showDeleteConfirm = true },
                    showChevron = false,
                    showDivider = false,
                )
            }
        }

        // ── ③ Real-time Recognition Testing ──────────────────────────
        if (isReady) {
            SettingsSection(
                header = "端侧离线识别实测",
                footer = "无需物理按键，点击下方按钮开始录音，停止后即刻展示本地 JNI 原生推断耗时与识别文本。",
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    if (testResultText != null) {
                        Text(
                            text = "识别结果：",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = if (testResultText.isNullOrBlank()) "（未检测到有效语音输入）" else testResultText!!,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                        if (testLatencyMs != null) {
                            Text(
                                text = "⚡ 端侧 CPU 推理耗时：${testLatencyMs} ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF34C759),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    Button(
                        onClick = {
                            if (isTestRecording) {
                                // Stop recording
                                isTestRecording = false
                            } else {
                                // Start recording
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                    != PackageManager.PERMISSION_GRANTED
                                ) {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    return@Button
                                }

                                isTestRecording = true
                                testResultText = "正在录音，请说话..."
                                testLatencyMs = null

                                testRecordingJob = scope.launch(Dispatchers.IO) {
                                    runAudioCaptureTest(context) { text, latency ->
                                        scope.launch(Dispatchers.Main) {
                                            testResultText = text
                                            testLatencyMs = latency
                                            isTestRecording = false
                                        }
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isTestRecording) Color(0xFFFF3B30) else MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Icon(
                                imageVector = if (isTestRecording) Icons.Outlined.Stop else Icons.Outlined.Mic,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (isTestRecording) "停止录音并推断" else "开始录音测试")
                        }
                    }
                }
            }
        }

        // ── ④ Advanced Inference Options ─────────────────────────────
        SettingsSection(
            header = "推理偏好与文本规整",
            footer = "逆文本正则化 (ITN) 会将语音中的“二零二六年十月”自动转换写成“2026年10月”。",
        ) {
            // Language selector
            SettingsRow(
                title = "语言识别模式",
                subtitle = when (language) {
                    "auto" -> "自动识别 (中/英/粤/日/韩)"
                    "zh" -> "中文普通话 (强制中文)"
                    "en" -> "英语 (English)"
                    "yue" -> "粤语 (Cantonese)"
                    "ja" -> "日语 (Japanese)"
                    "ko" -> "韩语 (Korean)"
                    else -> language
                },
                icon = Icons.Outlined.Language,
                iconColor = Color(0xFF5856D6),
                onClick = { showLanguagePicker = true },
                showChevron = true,
                showDivider = true,
            )

            // ITN Switch
            SettingsSwitchRow(
                title = "逆文本正则化 (ITN)",
                subtitle = "口语数字与日期转换为阿拉伯数字（如“一百二十三”变为“123”）",
                checked = useItn,
                onCheckedChange = { OnDeviceSpeechModelManager.setUseItn(it) },
                showDivider = true,
            )

            // Threads count
            SettingsRow(
                title = "CPU 推理线程数",
                subtitle = "$numThreads 核心并发 (建议 2 线程平衡性能与能耗)",
                icon = Icons.Outlined.Bolt,
                iconColor = Color(0xFF34C759),
                onClick = { showThreadsPicker = true },
                showChevron = true,
                showDivider = false,
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    // ── Dialogs ────────────────────────────────────────────────────────
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除离线语音模型？") },
            text = { Text("删除后将无法脱机进行语音识别，后续若需使用可随时再次下载。") },
            confirmButton = {
                TextButton(onClick = {
                    OnDeviceSpeechModelManager.deleteModel(context)
                    showDeleteConfirm = false
                }) { Text("确认删除", color = Color(0xFFFF3B30)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }

    if (showLanguagePicker) {
        val options = listOf(
            "auto" to "自动检测 (中/英/粤/日/韩)",
            "zh" to "中文普通话 (zh)",
            "en" to "英语 (en)",
            "yue" to "粤语 (yue)",
            "ja" to "日语 (ja)",
            "ko" to "韩语 (ko)",
        )
        AlertDialog(
            onDismissRequest = { showLanguagePicker = false },
            title = { Text("选择语言模式") },
            text = {
                Column {
                    options.forEach { (code, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    OnDeviceSpeechModelManager.setLanguage(code)
                                    showLanguagePicker = false
                                }
                                .padding(vertical = 6.dp),
                        ) {
                            RadioButton(
                                selected = language == code,
                                onClick = {
                                    OnDeviceSpeechModelManager.setLanguage(code)
                                    showLanguagePicker = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguagePicker = false }) { Text("关闭") }
            },
        )
    }

    if (showThreadsPicker) {
        val threadOptions = listOf(1, 2, 4)
        AlertDialog(
            onDismissRequest = { showThreadsPicker = false },
            title = { Text("选择 CPU 推理并发线程数") },
            text = {
                Column {
                    threadOptions.forEach { t ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    OnDeviceSpeechModelManager.setNumThreads(t)
                                    showThreadsPicker = false
                                }
                                .padding(vertical = 6.dp),
                        ) {
                            RadioButton(
                                selected = numThreads == t,
                                onClick = {
                                    OnDeviceSpeechModelManager.setNumThreads(t)
                                    showThreadsPicker = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = when (t) {
                                    1 -> "1 核心 (极度省电)"
                                    2 -> "2 核心 (推荐，平衡速度与发热)"
                                    4 -> "4 核心 (极速，单次推理 < 50ms)"
                                    else -> "$t 核心"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThreadsPicker = false }) { Text("关闭") }
            },
        )
    }
}

@SuppressLint("MissingPermission")
private suspend fun runAudioCaptureTest(
    context: Context,
    onResult: (text: String, latencyMs: Long) -> Unit,
) = withContext(Dispatchers.IO) {
    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    val record = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, minBuf.coerceAtLeast(sampleRate * 2))

    val baos = ByteArrayOutputStream()
    val buf = ByteArray(1024)

    try {
        record.startRecording()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 3500L) { // Record up to 3.5s
            val read = record.read(buf, 0, buf.size)
            if (read > 0) {
                baos.write(buf, 0, read)
            }
        }
    } catch (_: Exception) {
    } finally {
        try { record.stop() } catch (_: Exception) {}
        record.release()
    }

    val engine = SherpaOnnxSpeechRecognitionEngine(context.applicationContext)
    val (text, latency) = engine.transcribeAudio(baos.toByteArray())
    onResult(text, latency)
}
