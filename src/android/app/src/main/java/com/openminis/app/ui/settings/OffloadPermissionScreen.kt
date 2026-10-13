package com.openminis.app.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * [T-android-permissions-redesign] The permissions screen, reorganised around
 * one question: **what can the agent reach that it cannot reach today, and how
 * do I change that?**
 *
 * The previous layout answered a different question — "what is the state of
 * each of eighteen registered capabilities?" — which on a fresh install meant
 * sixteen rows of which thirteen read "Bypass" and needed no decision. Two
 * further things it got wrong:
 *
 *  - It conflated **policy** (may the agent use this) with **grants** (has the
 *    OS given us anything). The two are orthogonal and were rendered in the
 *    same column, so a row could say "Bypass" while Android had long since
 *    revoked the underlying permission.
 *  - `root_cli` had no row at all. It is gated at NOT_ALLOWED by default, so a
 *    user who enabled nothing got `permission_denied` pointing at a page with
 *    nothing to click.
 *
 * The layout follows from the fix: the three capabilities that ship denied get
 * real rows with real status, anything the user has changed away from its
 * default floats to the top of its own group, and the fourteen default-allowed
 * rows collapse behind a single summary line until asked for.
 */
@Composable
fun OffloadPermissionScreen(
    onBack: () -> Unit,
    onOpenPrivilegedBackend: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val levels by OffloadPermissionManager.levels.collectAsState()
    val configEnabled by MinisConfigPermissionStore.enabled.collectAsState()

    var showResetConfirm by remember { mutableStateOf(false) }
    var dataExpanded by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }

    val a11yEnabled by A11yState.isEnabled.collectAsState()
    var a11yConnected by remember { mutableStateOf(MinisAccessibilityService.getInstance() != null) }
    var a11yRevoked by remember { mutableStateOf(false) }
    var a11yRestricted by remember { mutableStateOf(false) }
    var unrestricting by remember { mutableStateOf(false) }
    var unrestrictFailed by remember { mutableStateOf(false) }
    var repairing by remember { mutableStateOf(false) }
    var repairFailed by remember { mutableStateOf(false) }
    var shizukuReady by remember { mutableStateOf(ShizukuManager.isReady()) }

    LaunchedEffect(Unit) {
        A11yState.init(context)
        while (true) {
            A11yState.refresh(context)
            val inSettings = A11yState.isEnabled.value
            val connected = MinisAccessibilityService.getInstance() != null
            a11yConnected = connected
            // "Revoked" is only claimed once the grant was actually held at
            // least once — otherwise a first-run user is shown a repair prompt
            // for something that was never broken.
            a11yRevoked = !inSettings && !connected &&
                AccessibilityRecoveryManager.hasEverBeenGranted(context)
            a11yRestricted = !inSettings && RestrictedSettingsManager.isRestricted(context)
            shizukuReady = ShizukuManager.isReady()
            delay(1000)
        }
    }

    val registry = OffloadPermissionManager.toolRegistry
    val dataTools = registry.filter {
        it.showInSettings &&
            (it.toolName in DATA_TOOLS)
    }
    val fileTools = registry.filter { it.toolName in FILE_TOOLS }
    val advancedTools = registry.filter {
        it.showInSettings &&
            it.toolName !in DATA_TOOLS &&
            it.toolName !in FILE_TOOLS &&
            it.toolName !in PRIVILEGED_TOOLS
    }
    val customised = registry.filter { it.showInSettings && isCustomised(it.toolName) }

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
        // ── ① Privileged backends ────────────────────────────────────────
        // The only three capabilities that ship DENIED. Everything else in this
        // screen is, by default, already switched on.
        SettingsSection(
            header = stringResource(R.string.perm_section_privileged_backend),
            footer = stringResource(R.string.perm_privileged_footer),
        ) {
            // Root
            CapabilityRow(
                title = stringResource(R.string.perm_root_cli_title),
                subtitle = stringResource(R.string.perm_root_cli_sub),
                icon = Icons.Outlined.Lock,
                iconColor = Color(0xFFFF3B30),
                level = levels["root_cli"] ?: PermissionLevel.ASK_ONCE,
                onLevelChange = { OffloadPermissionManager.setLevel("root_cli", it) },
                statusText = stringResource(R.string.perm_root_status),
                statusColor = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { onOpenPrivilegedBackend() },
                showDivider = true,
            )

            // Shizuku
            CapabilityRow(
                title = stringResource(R.string.perm_tool_shizuku_cli),
                subtitle = stringResource(R.string.perm_shizuku_cli_sub),
                icon = Icons.Outlined.Shield,
                iconColor = Color(0xFFAF52DE),
                level = levels["shizuku_cli"] ?: PermissionLevel.NOT_ALLOWED,
                onLevelChange = { OffloadPermissionManager.setLevel("shizuku_cli", it) },
                statusText = shizukuStateLabel(),
                statusColor = shizukuStateColor(),
                onClick = { onOpenPrivilegedBackend() },
                showDivider = true,
            )

            // Accessibility
            CapabilityRow(
                title = stringResource(R.string.perm_tool_a11y_cli),
                subtitle = stringResource(R.string.perm_a11y_cli_sub),
                icon = Icons.Outlined.Accessibility,
                iconColor = Color(0xFF34C759),
                level = levels["a11y_cli"] ?: PermissionLevel.NOT_ALLOWED,
                onLevelChange = { OffloadPermissionManager.setLevel("a11y_cli", it) },
                statusText = stringResource(
                    if (a11yEnabled && a11yConnected) R.string.perm_state_on
                    else R.string.perm_state_off
                ),
                statusColor = if (a11yEnabled && a11yConnected) Color(0xFF34C759)
                else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { A11yState.openSettings(context) },
                showDivider = false,
            )
        }

        // ── ② Anything the user has changed away from its default ─────────
        // The answer to "what did I actually turn off" without making the user
        // scroll past sixteen rows to find out.
        SettingsSection(
            header = stringResource(R.string.perm_section_your_changes),
            footer = null,
        ) {
            if (customised.isEmpty()) {
                NoRestrictionsRow()
            } else {
                customised.forEachIndexed { index, info ->
                    CapabilityRow(
                        title = capabilityTitle(info.toolName),
                        subtitle = capabilitySubtitle(info.toolName),
                        icon = capabilityIcon(info.toolName),
                        iconColor = capabilityTint(info.toolName),
                        level = levels[info.toolName] ?: info.defaultLevel,
                        onLevelChange = { OffloadPermissionManager.setLevel(info.toolName, it) },
                        showDivider = index < customised.lastIndex,
                    )
                }
            }
        }

        // ── ③ Private data ───────────────────────────────────────────────
        CollapsibleCapabilityGroup(
            title = stringResource(R.string.perm_section_data),
            summary = groupSummary(dataTools.map { it.toolName }),
            footer = stringResource(R.string.perm_data_footer),
            icon = Icons.Outlined.Lock,
            tint = Color(0xFF5856D6),
            expanded = dataExpanded,
            onToggle = { dataExpanded = !dataExpanded },
        ) {
            dataTools.forEachIndexed { index, info ->
                CapabilityRow(
                    title = capabilityTitle(info.toolName),
                    subtitle = capabilitySubtitle(info.toolName),
                    icon = capabilityIcon(info.toolName),
                    iconColor = capabilityTint(info.toolName),
                    level = levels[info.toolName] ?: info.defaultLevel,
                    onLevelChange = { OffloadPermissionManager.setLevel(info.toolName, it) },
                    showDivider = index < dataTools.lastIndex,
                )
            }
        }

        // ── ④ More ───────────────────────────────────────────────────────
        CollapsibleCapabilityGroup(
            title = stringResource(R.string.perm_section_more),
            summary = groupSummary(fileTools.map { it.toolName } + advancedTools.map { it.toolName }),
            footer = stringResource(R.string.perm_more_footer),
            icon = Icons.Outlined.Folder,
            tint = Color(0xFFFF9500),
            expanded = advancedExpanded,
            onToggle = { advancedExpanded = !advancedExpanded },
        ) {
            (fileTools + advancedTools).forEachIndexed { index, info ->
                CapabilityRow(
                    title = capabilityTitle(info.toolName),
                    subtitle = capabilitySubtitle(info.toolName),
                    icon = capabilityIcon(info.toolName),
                    iconColor = capabilityTint(info.toolName),
                    level = levels[info.toolName] ?: info.defaultLevel,
                    onLevelChange = { OffloadPermissionManager.setLevel(info.toolName, it) },
                    showDivider = index < fileTools.size + advancedTools.size - 1,
                )
            }
            SettingsSwitchRow(
                title = stringResource(R.string.perm_allow_minis_config),
                subtitle = stringResource(R.string.perm_minis_config_desc),
                checked = configEnabled,
                onCheckedChange = { MinisConfigPermissionStore.setEnabled(it) },
                showDivider = false,
            )
        }

        // ── System grants that are actually in the way ────────────────────
        // Only rows with something to fix. Everything already satisfied
        // collapses to one summary line, because a settings screen that
        // celebrates what is already fine is noise.
        val systemIssues = buildList {
            if (!a11yEnabled && a11yRestricted) {
                add("restricted")
            }
            if (a11yRevoked) {
                add("revoked")
            }
            if (a11yEnabled && !a11yConnected &&
                PowerOptimizationManager.needsOemAutostartGuidance()
            ) {
                add("oem")
            }
        }
        SettingsSection(
            header = stringResource(R.string.perm_section_system_grants),
            footer = stringResource(R.string.perm_system_grants_footer),
        ) {
            if (systemIssues.isEmpty()) {
                AllHealthyRow(stringResource(R.string.perm_system_grants_ok), 3)
            } else {
                var first = true
                if ("restricted" in systemIssues) {
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
                        showDivider = true,
                    )
                    first = false
                }
                if ("revoked" in systemIssues) {
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
                        showDivider = first,
                    )
                    first = false
                }
                if ("oem" in systemIssues) {
                    val activity = context as? Activity
                    SettingsRow(
                        icon = Icons.Outlined.RestartAlt,
                        iconColor = Color(0xFFFF9500),
                        title = stringResource(
                            R.string.system_permissions_a11y_oem_autostart
                        ),
                        subtitle = stringResource(
                            R.string.system_permissions_a11y_oem_autostart_sub
                        ),
                        onClick = {
                            activity?.let {
                                if (!PowerOptimizationManager
                                        .openOemAutostartSettings(it)
                                ) {
                                    PowerOptimizationManager
                                        .openAppDetailsSettings(it)
                                }
                            }
                        },
                        showDivider = first,
                    )
                    first = false
                    SettingsRow(
                        icon = Icons.Outlined.BatteryAlert,
                        iconColor = Color(0xFFFF9500),
                        title = stringResource(R.string.system_permissions_a11y_oem_battery),
                        subtitle = stringResource(
                            R.string.system_permissions_a11y_oem_battery_sub
                        ),
                        onClick = {
                            activity?.let {
                                if (!PowerOptimizationManager
                                        .requestBatteryOptimizationExemption(it)
                                ) {
                                    PowerOptimizationManager
                                        .openAppDetailsSettings(it)
                                }
                            }
                        },
                        showDivider = false,
                    )
                }
            }
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

// ── presentation helpers ────────────────────────────────────────────────

private val DATA_TOOLS = setOf("calendar", "location", "clipboard", "contacts", "photos")

/**
 * [T-android-tool-surface] File tools live here rather than under "Data": they
 * are the only capabilities whose tri-state governs something narrower than
 * their name suggests, and the footer says so rather than the row overstating
 * it.
 */
private val FILE_TOOLS = setOf("read", "write", "edit")

private val PRIVILEGED_TOOLS = setOf("root_cli", "shizuku_cli", "a11y_cli")

@Composable
private fun isCustomised(toolName: String) =
    OffloadPermissionManager.isCustomised(toolName)

@Composable
private fun capabilityTitle(toolName: String): String = stringResource(
    when (toolName) {
        "calendar" -> R.string.perm_tool_calendar
        "location" -> R.string.perm_tool_location
        "clipboard" -> R.string.perm_tool_clipboard
        "contacts" -> R.string.perm_tool_contacts
        "photos" -> R.string.perm_tool_photos
        "read" -> R.string.perm_tool_read
        "write" -> R.string.perm_tool_write
        "edit" -> R.string.perm_tool_edit
        "a11y_cli" -> R.string.perm_tool_a11y_cli
        "shizuku_cli" -> R.string.perm_tool_shizuku_cli
        "root_cli" -> R.string.perm_root_cli_title
        else -> R.string.perm_section_more
    }
)

/**
 * Q3 decision: state the boundary rather than imply more than the code does.
 * The tri-state on read/write/edit only fires for paths under /data — the
 * protected-path refusal is unconditional and separate. The previous footer
 * claimed the switch covered ordinary file access, which it never did.
 */
@Composable
private fun capabilitySubtitle(toolName: String): String? = when (toolName) {
    "read" -> stringResource(R.string.perm_tool_read_sub)
    "write" -> stringResource(R.string.perm_tool_write_sub)
    "edit" -> stringResource(R.string.perm_tool_edit_sub)
    else -> null
}

private fun capabilityIcon(toolName: String): ImageVector = when (toolName) {
    "calendar" -> Icons.Outlined.CalendarMonth
    "location" -> Icons.Outlined.LocationOn
    "clipboard" -> Icons.Outlined.Description
    "contacts" -> Icons.Outlined.Contacts
    "photos" -> Icons.Outlined.Image
    "read" -> Icons.Outlined.Description
    "write" -> Icons.Outlined.Folder
    "edit" -> Icons.Outlined.EditNote
    "root_cli" -> Icons.Outlined.Lock
    "shizuku_cli" -> Icons.Outlined.Shield
    "a11y_cli" -> Icons.Outlined.Accessibility
    else -> Icons.Outlined.Terminal
}

private fun capabilityTint(toolName: String): Color = when (toolName) {
    "root_cli" -> Color(0xFFFF3B30)
    "shizuku_cli" -> Color(0xFFAF52DE)
    "a11y_cli" -> Color(0xFF34C759)
    "location" -> Color(0xFF34C759)
    "read" -> Color(0xFF32ADE6)
    "write" -> Color(0xFF007AFF)
    "edit" -> Color(0xFFFF9500)
    else -> Color(0xFF5856D6)
}

@Composable
private fun groupSummary(toolNames: List<String>): String {
    val n = toolNames.size
    val changed = toolNames.count { OffloadPermissionManager.isCustomised(it) }
    return stringResource(
        if (changed > 0) R.string.perm_group_customised else R.string.perm_group_default,
        n,
        changed,
    )
}

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
