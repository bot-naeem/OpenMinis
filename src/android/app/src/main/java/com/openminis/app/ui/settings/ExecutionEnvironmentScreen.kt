package com.openminis.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.sandbox.ExecutionEnvironment
import com.openminis.app.sandbox.ExecutionEnvironmentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings Screen for choosing Agent Execution Environment:
 * Sandbox (PRoot Alpine) / Android Host Shell / Shizuku ADB / Root (su).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExecutionEnvironmentScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeEnv by ExecutionEnvironmentRepository.currentEnvironment.collectAsState()

    var showRootConfirmDialog by remember { mutableStateOf(false) }

    if (showRootConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRootConfirmDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF3B30)) },
            title = { Text(stringResource(R.string.exec_env_root_warning_title)) },
            text = { Text(stringResource(R.string.exec_env_root_warning_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRootConfirmDialog = false
                        scope.launch {
                            val available = withContext(Dispatchers.IO) {
                                ExecutionEnvironmentRepository.isRootAvailable()
                            }
                            if (available) {
                                ExecutionEnvironmentRepository.setEnvironment(context, ExecutionEnvironment.ROOT)
                                Toast.makeText(context, R.string.exec_env_root_title, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, R.string.exec_env_root_not_detected, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                ) {
                    Text(stringResource(android.R.string.ok), color = Color(0xFFFF3B30))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRootConfirmDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_execution_environment)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSection(
                header = stringResource(R.string.exec_env_header),
                footer = stringResource(R.string.exec_env_footer),
            ) {
                ExecutionEnvironment.entries.forEachIndexed { index, env ->
                    val isSelected = activeEnv == env
                    val (icon, iconColor) = when (env) {
                        ExecutionEnvironment.SANDBOX -> Icons.Outlined.Inventory2 to Color(0xFF007AFF)
                        ExecutionEnvironment.HOST_SHELL -> Icons.Outlined.PhoneAndroid to Color(0xFF34C759)
                        ExecutionEnvironment.SHIZUKU -> Icons.Outlined.Shield to Color(0xFF5856D6)
                        ExecutionEnvironment.ROOT -> Icons.Outlined.Terminal to Color(0xFFFF3B30)
                    }

                    EnvironmentItemRow(
                        title = stringResource(env.titleRes),
                        description = stringResource(env.descRes),
                        icon = icon,
                        iconColor = iconColor,
                        selected = isSelected,
                        onClick = {
                            when (env) {
                                ExecutionEnvironment.ROOT -> {
                                    if (!isSelected) {
                                        showRootConfirmDialog = true
                                    }
                                }
                                ExecutionEnvironment.SHIZUKU -> {
                                    if (ExecutionEnvironmentRepository.isShizukuReady()) {
                                        ExecutionEnvironmentRepository.setEnvironment(context, env)
                                    } else {
                                        Toast.makeText(context, R.string.exec_env_shizuku_not_ready, Toast.LENGTH_LONG).show()
                                        // Still allow switching so user can activate Shizuku
                                        ExecutionEnvironmentRepository.setEnvironment(context, env)
                                    }
                                }
                                else -> {
                                    ExecutionEnvironmentRepository.setEnvironment(context, env)
                                }
                            }
                        },
                        showDivider = index < ExecutionEnvironment.entries.size - 1
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun EnvironmentItemRow(
    title: String,
    description: String,
    icon: ImageVector,
    iconColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
    showDivider: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }
        Spacer(Modifier.width(8.dp))
        RadioButton(
            selected = selected,
            onClick = onClick
        )
    }
}
