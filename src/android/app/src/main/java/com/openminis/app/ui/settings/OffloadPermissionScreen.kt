package com.openminis.app.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.accessibility.A11yState
import com.openminis.app.accessibility.AccessibilityRecoveryManager
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.accessibility.RestrictedSettingsManager
import com.openminis.app.config.MinisConfigPermissionStore
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.offload.OffloadPermissionManager.PermissionLevel
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.power.PowerOptimizationManager
import com.openminis.app.sandbox.RootAccess
import com.openminis.app.tools.AgentToolSwitch
import com.openminis.app.ui.aurora.AuroraGlowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * System Privilege & Automation Control Center (tailored for rooted Android power users).
 *
 * Replaces the legacy 18-row offload permissions with 4 cohesive atomic control domains:
 * 1. Host Root Privilege (bash as_root policies, RootCommandGuard brick protection, audit logs)
 * 2. Screen Perception & GUI Automation (screen_view / screen_touch, pipeline status, A11y self-healing)
 * 3. Host Filesystem & Storage (/data app isolation, /sdcard, minis-config protection)
 * 4. Sandbox Browser (browser_use web automation isolation)
 */
@Composable
fun OffloadPermissionScreen(
    onBack: () -> Unit,
    onOpenPrivilegedBackend: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val levels by OffloadPermissionManager.levels.collectAsState()
    val configEnabled by MinisConfigPermissionStore.enabled.collectAsState()

    var screenEnabled by remember {
        mutableStateOf(AgentToolSwitch.SCREEN.isEnabled(context))
    }
    var browserEnabled by remember {
        mutableStateOf(AgentToolSwitch.BROWSER.isEnabled(context))
    }

    var isRequestingRoot by remember { mutableStateOf(false) }
    var rootGranted by remember { mutableStateOf(RootAccess.isRootGranted()) }
    var rootInstalled by remember { mutableStateOf(RootAccess.isRootInstalled()) }
    var overlayGranted by remember { mutableStateOf(AuroraGlowManager.hasOverlayPermission(context)) }

    val a11yEnabled by A11yState.isEnabled.collectAsState()
    var a11yConnected by remember { mutableStateOf(MinisAccessibilityService.getInstance() != null) }
    var a11yRevoked by remember { mutableStateOf(false) }
    var a11yRestricted by remember { mutableStateOf(false) }
    var unrestricting by remember { mutableStateOf(false) }
    var unrestrictFailed by remember { mutableStateOf(false) }
    var repairing by remember { mutableStateOf(false) }
    var repairFailed by remember { mutableStateOf(false) }
    var shizukuReady by remember { mutableStateOf(ShizukuManager.isReady()) }

    var showResetConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        A11yState.init(context)
        while (true) {
            A11yState.refresh(context)
            val inSettings = A11yState.isEnabled.value
            val connected = MinisAccessibilityService.getInstance() != null
            a11yConnected = connected
            a11yRevoked = !inSettings && !connected &&
                AccessibilityRecoveryManager.hasEverBeenGranted(context)
            a11yRestricted = !inSettings && RestrictedSettingsManager.isRestricted(context)
            shizukuReady = ShizukuManager.isReady()
            overlayGranted = AuroraGlowManager.hasOverlayPermission(context)
            if (!isRequestingRoot) {
                rootGranted = RootAccess.isRootGranted()
                rootInstalled = RootAccess.isRootInstalled()
            }
            delay(2000)
        }
    }

    val currentRootLevel = levels["root_cli"] ?: PermissionLevel.ASK_ONCE

    SettingsScaffold(
        title = stringResource(R.string.perm_title),
        onBack = onBack,
        actions = {
            Text(
                text = stringResource(R.string.perm_reset_all),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { showResetConfirm = true },
            )
        },
    ) {
        // ── ① Host Root Privilege (bash as_root) ──────────────────────────
        SettingsSection(
            header = stringResource(R.string.perm_section_root_privilege),
            footer = stringResource(R.string.perm_section_root_privilege_footer),
        ) {
            // Root Status
            SettingsRow(
                title = stringResource(R.string.perm_root_cli_title),
                subtitle = when {
                    isRequestingRoot -> "正在请求 Superuser 授权..."
                    rootGranted -> "su (UID 0) · Magisk / KernelSU"
                    rootInstalled -> "su " + stringResource(R.string.shizuku_badge_need_permission) + " · 点击申请授权"
                    else -> stringResource(R.string.perm_state_not_installed)
                },
                icon = Icons.Outlined.Lock,
                iconColor = Color(0xFFFF3B30),
                onClick = {
                    if (isRequestingRoot) return@SettingsRow
                    if (rootGranted) {
                        Toast.makeText(context, "Root 权限已就绪 (UID 0)", Toast.LENGTH_SHORT).show()
                        return@SettingsRow
                    }
                    isRequestingRoot = true
                    scope.launch(Dispatchers.IO) {
                        val res = RootAccess.requestRoot(15_000L)
                        val ok = res != null && res.exitCode == 0 && res.stdout.contains("uid=0")
                        withContext(Dispatchers.Main) {
                            rootGranted = ok
                            isRequestingRoot = false
                            if (ok) {
                                Toast.makeText(context, "Root 授权成功 (UID 0)", Toast.LENGTH_SHORT).show()
                                if (!overlayGranted && AuroraGlowManager.tryAutoGrantOverlayWithRoot(context)) {
                                    overlayGranted = true
                                }
                            } else {
                                Toast.makeText(context, "未获得 Root 授权，请在 Magisk/KernelSU 中允许 Minis", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                showChevron = !rootGranted,
                trailing = {
                    Text(
                        text = if (rootGranted) stringResource(R.string.shizuku_badge_ready) else stringResource(R.string.perm_state_off),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (rootGranted) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                showDivider = true,
            )

            // Policy Option 1: ASK_ONCE (Recommended)
            SettingsRow(
                title = stringResource(R.string.perm_root_policy_ask_session),
                subtitle = stringResource(R.string.perm_root_policy_ask_session_sub),
                onClick = { OffloadPermissionManager.setLevel("root_cli", PermissionLevel.ASK_ONCE) },
                showChevron = false,
                trailing = {
                    RadioButton(
                        selected = currentRootLevel == PermissionLevel.ASK_ONCE,
                        onClick = { OffloadPermissionManager.setLevel("root_cli", PermissionLevel.ASK_ONCE) },
                        colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
                    )
                },
                showDivider = true,
            )

            // Policy Option 2: BYPASS (Always Allow / Geek Mode)
            SettingsRow(
                title = stringResource(R.string.perm_root_policy_always),
                subtitle = stringResource(R.string.perm_root_policy_always_sub),
                onClick = { OffloadPermissionManager.setLevel("root_cli", PermissionLevel.BYPASS) },
                showChevron = false,
                trailing = {
                    RadioButton(
                        selected = currentRootLevel == PermissionLevel.BYPASS,
                        onClick = { OffloadPermissionManager.setLevel("root_cli", PermissionLevel.BYPASS) },
                        colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
                    )
                },
                showDivider = true,
            )

            // Policy Option 3: NOT_ALLOWED (Disabled)
            SettingsRow(
                title = stringResource(R.string.perm_root_policy_disabled),
                subtitle = stringResource(R.string.perm_root_policy_disabled_sub),
                onClick = { OffloadPermissionManager.setLevel("root_cli", PermissionLevel.NOT_ALLOWED) },
                showChevron = false,
                trailing = {
                    RadioButton(
                        selected = currentRootLevel == PermissionLevel.NOT_ALLOWED,
                        onClick = { OffloadPermissionManager.setLevel("root_cli", PermissionLevel.NOT_ALLOWED) },
                        colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
                    )
                },
                showDivider = true,
            )

            // RootCommandGuard Protection Status
            SettingsRow(
                title = stringResource(R.string.perm_guard_title),
                subtitle = stringResource(R.string.perm_guard_sub),
                icon = Icons.Outlined.Shield,
                iconColor = Color(0xFF34C759),
                showChevron = false,
                trailing = {
                    Text(
                        text = stringResource(R.string.perm_guard_active),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF34C759),
                    )
                },
                showDivider = true,
            )

            // Root Audit Logs
            SettingsRow(
                title = stringResource(R.string.perm_root_audit_title),
                subtitle = stringResource(R.string.perm_root_audit_sub),
                icon = Icons.Outlined.Description,
                iconColor = Color(0xFF007AFF),
                onClick = onOpenLogs,
                showChevron = true,
                showDivider = false,
            )
        }

        // ── ② Screen Perception & GUI Automation (screen_view / screen_touch) ──
        SettingsSection(
            header = stringResource(R.string.perm_section_screen_automation),
            footer = stringResource(R.string.perm_section_screen_automation_footer),
        ) {
            // Master Switch
            SettingsSwitchRow(
                title = stringResource(R.string.perm_screen_tools_title),
                subtitle = stringResource(R.string.perm_screen_tools_sub),
                checked = screenEnabled,
                onCheckedChange = { enabled ->
                    screenEnabled = enabled
                    AgentToolSwitch.SCREEN.setEnabled(context, enabled)
                    OffloadPermissionManager.setLevel(
                        "screen",
                        if (enabled) PermissionLevel.BYPASS else PermissionLevel.NOT_ALLOWED,
                    )
                },
                icon = Icons.Outlined.PhoneAndroid,
                iconColor = Color(0xFF34C759),
                showDivider = true,
            )

            // Aurora Edge Glow (Overlay Permission)
            SettingsRow(
                title = "全屏极光流光 (悬浮窗权限)",
                subtitle = if (overlayGranted) "已授权 · 屏幕四周渐变极光流光正常展现" else "未授权 · 点击开启“在其他应用上层显示”",
                icon = Icons.Outlined.AutoAwesome,
                iconColor = Color(0xFF00F5D4),
                onClick = {
                    if (!overlayGranted) {
                        if (AuroraGlowManager.tryAutoGrantOverlayWithRoot(context)) {
                            overlayGranted = true
                            Toast.makeText(context, "已通过 Root 自动开启悬浮窗权限", Toast.LENGTH_SHORT).show()
                        } else {
                            AuroraGlowManager.openOverlaySettings(context)
                        }
                    } else {
                        Toast.makeText(context, "全屏流光权限已就绪", Toast.LENGTH_SHORT).show()
                    }
                },
                showChevron = !overlayGranted,
                trailing = {
                    Text(
                        text = if (overlayGranted) stringResource(R.string.perm_state_on) else stringResource(R.string.perm_state_off),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (overlayGranted) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                showDivider = true,
            )

            // Pipeline 1: Root Pipeline (Priority)
            SettingsRow(
                title = stringResource(R.string.perm_pipeline_root_title),
                subtitle = stringResource(R.string.perm_pipeline_root_sub),
                icon = Icons.Outlined.Terminal,
                iconColor = Color(0xFFFF3B30),
                showChevron = false,
                trailing = {
                    Text(
                        text = stringResource(if (rootGranted) R.string.perm_state_on else R.string.perm_state_off),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (rootGranted) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                showDivider = true,
            )

            // Pipeline 2: Shizuku / Sui Binder
            SettingsRow(
                title = stringResource(R.string.perm_pipeline_shizuku_title),
                subtitle = stringResource(R.string.perm_pipeline_shizuku_sub),
                icon = Icons.Outlined.Shield,
                iconColor = Color(0xFFAF52DE),
                onClick = onOpenPrivilegedBackend,
                showChevron = true,
                trailing = {
                    Text(
                        text = shizukuStateLabel(),
                        style = MaterialTheme.typography.labelLarge,
                        color = shizukuStateColor(),
                    )
                },
                showDivider = true,
            )

            // Pipeline 3: Accessibility Service (Fallback / SoM marks)
            val hasSystemIssues = a11yRestricted || a11yRevoked || (a11yEnabled && !a11yConnected && PowerOptimizationManager.needsOemAutostartGuidance())
            SettingsRow(
                title = stringResource(R.string.perm_pipeline_a11y_title),
                subtitle = stringResource(R.string.perm_pipeline_a11y_sub),
                icon = Icons.Outlined.Accessibility,
                iconColor = Color(0xFF34C759),
                onClick = { A11yState.openSettings(context) },
                showChevron = true,
                trailing = {
                    Text(
                        text = stringResource(
                            if (a11yEnabled && a11yConnected) R.string.perm_state_on
                            else R.string.perm_state_off
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (a11yEnabled && a11yConnected) Color(0xFF34C759)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                showDivider = hasSystemIssues,
            )

            // Diagnostic & One-Click Repair Rows
            if (a11yRestricted) {
                SettingsRow(
                    icon = Icons.Outlined.Lock,
                    iconColor = Color(0xFFFF9500),
                    title = stringResource(R.string.system_permissions_a11y_restricted_header),
                    subtitle = stringResource(
                        if (unrestricting) R.string.system_permissions_a11y_restricted_shizuku_sub
                        else if (unrestrictFailed) R.string.system_permissions_a11y_restricted_failed
                        else R.string.system_permissions_a11y_restricted_shizuku
                    ),
                    onClick = {
                        if (unrestricting) return@SettingsRow
                        unrestricting = true
                        unrestrictFailed = false
                        scope.launch {
                            val ok = RestrictedSettingsManager.clearWithShizuku(context)
                            unrestricting = false
                            unrestrictFailed = !ok
                        }
                    },
                    showDivider = a11yRevoked,
                )
            }
            if (a11yRevoked) {
                SettingsRow(
                    icon = Icons.Outlined.Build,
                    iconColor = Color(0xFFFF3B30),
                    title = stringResource(
                        if (shizukuReady) R.string.a11y_repair_row_shizuku
                        else R.string.a11y_repair_row_manual
                    ),
                    subtitle = when {
                        repairing -> stringResource(R.string.a11y_repair_in_progress)
                        repairFailed -> stringResource(R.string.a11y_repair_failed)
                        else -> stringResource(R.string.a11y_repair_row_sub)
                    },
                    onClick = {
                        if (!shizukuReady) {
                            A11yState.openSettings(context)
                            return@SettingsRow
                        }
                        if (repairing) return@SettingsRow
                        repairing = true
                        repairFailed = false
                        scope.launch {
                            val ok = AccessibilityRecoveryManager.repairWithShizuku(context)
                            repairing = false
                            repairFailed = !ok
                        }
                    },
                    showDivider = false,
                )
            }
            if (a11yEnabled && !a11yConnected && PowerOptimizationManager.needsOemAutostartGuidance()) {
                val activity = context as? Activity
                SettingsRow(
                    icon = Icons.Outlined.RestartAlt,
                    iconColor = Color(0xFFFF9500),
                    title = stringResource(R.string.system_permissions_a11y_oem_autostart),
                    subtitle = stringResource(R.string.system_permissions_a11y_oem_autostart_sub),
                    onClick = {
                        activity?.let {
                            if (!PowerOptimizationManager.openOemAutostartSettings(it)) {
                                PowerOptimizationManager.openAppDetailsSettings(it)
                            }
                        }
                    },
                    showDivider = true,
                )
                SettingsRow(
                    icon = Icons.Outlined.BatteryAlert,
                    iconColor = Color(0xFFFF9500),
                    title = stringResource(R.string.system_permissions_a11y_oem_battery),
                    subtitle = stringResource(R.string.system_permissions_a11y_oem_battery_sub),
                    onClick = {
                        activity?.let {
                            if (!PowerOptimizationManager.requestBatteryOptimizationExemption(it)) {
                                PowerOptimizationManager.openAppDetailsSettings(it)
                            }
                        }
                    },
                    showDivider = false,
                )
            }
        }

        // ── ③ Host Filesystem & Storage (/data & /sdcard) ──────────────────
        SettingsSection(
            header = stringResource(R.string.perm_section_host_storage),
            footer = stringResource(R.string.perm_section_host_storage_footer),
        ) {
            // /data Private App Data Protection (read / write / edit)
            val fileLevel = levels["write"] ?: PermissionLevel.BYPASS
            CapabilityRow(
                title = stringResource(R.string.perm_storage_data_title),
                subtitle = stringResource(R.string.perm_storage_data_sub),
                icon = Icons.Outlined.Folder,
                iconColor = Color(0xFFFF9500),
                level = fileLevel,
                onLevelChange = { newLevel ->
                    OffloadPermissionManager.setLevel("write", newLevel)
                    OffloadPermissionManager.setLevel("read", newLevel)
                    OffloadPermissionManager.setLevel("edit", newLevel)
                },
                showDivider = true,
            )

            // /sdcard External Storage Access
            SettingsRow(
                title = stringResource(R.string.perm_storage_sdcard_title),
                subtitle = stringResource(R.string.perm_storage_sdcard_sub),
                icon = Icons.Outlined.FolderShared,
                iconColor = Color(0xFF34C759),
                showChevron = false,
                trailing = {
                    Text(
                        text = stringResource(R.string.perm_state_on),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF34C759),
                    )
                },
                showDivider = true,
            )

            // minis-config Tool Modification Switch
            SettingsSwitchRow(
                title = stringResource(R.string.perm_allow_minis_config),
                subtitle = stringResource(R.string.perm_minis_config_desc),
                checked = configEnabled,
                onCheckedChange = { MinisConfigPermissionStore.setEnabled(it) },
                icon = Icons.Outlined.Build,
                iconColor = Color(0xFF007AFF),
                showDivider = false,
            )
        }

        // ── ④ Sandbox Browser (browser_use) ──────────────────────────────
        SettingsSection(
            header = stringResource(R.string.perm_section_sandbox_browser),
            footer = stringResource(R.string.perm_section_sandbox_browser_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.perm_browser_use_title),
                subtitle = stringResource(R.string.perm_browser_use_sub),
                checked = browserEnabled,
                onCheckedChange = { enabled ->
                    browserEnabled = enabled
                    AgentToolSwitch.BROWSER.setEnabled(context, enabled)
                    OffloadPermissionManager.setLevel(
                        "browser_use",
                        if (enabled) PermissionLevel.BYPASS else PermissionLevel.NOT_ALLOWED,
                    )
                },
                icon = Icons.Outlined.Public,
                iconColor = Color(0xFF007AFF),
                showDivider = false,
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.perm_reset_confirm_title)) },
            text = { Text(stringResource(R.string.perm_reset_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    OffloadPermissionManager.resetAll()
                    MinisConfigPermissionStore.setEnabled(true)
                    AgentToolSwitch.SCREEN.setEnabled(context, true)
                    AgentToolSwitch.BROWSER.setEnabled(context, true)
                    screenEnabled = true
                    browserEnabled = true
                    showResetConfirm = false
                }) { Text(stringResource(R.string.perm_reset_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────

@Composable
private fun shizukuStateLabel(): String = when {
    ShizukuManager.isReady() -> stringResource(R.string.perm_state_on)
    ShizukuManager.isInstalled() -> stringResource(R.string.perm_state_not_running)
    else -> stringResource(R.string.perm_state_not_installed)
}

@Composable
private fun shizukuStateColor(): Color = when {
    ShizukuManager.isReady() -> Color(0xFF34C759)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Open Minis' own App info page — "Allow restricted settings" lives in ⋮. */
internal fun openAppDetailsSettings(context: android.content.Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    } catch (e: Exception) {
        AppLogger.warning("Permissions", "could not open app details: ${e.message}")
    }
}
